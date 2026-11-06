"""Windows child ownership. A closed supervisor job retires its entire child tree."""
import ctypes as c
from ctypes import wintypes as w
import os
import subprocess
import threading


class Basic(c.Structure):
    _fields_ = [("process_time", c.c_longlong), ("job_time", c.c_longlong),
                ("flags", w.DWORD), ("min_ws", c.c_size_t), ("max_ws", c.c_size_t),
                ("active_limit", w.DWORD), ("affinity", c.c_size_t),
                ("priority", w.DWORD), ("scheduling", w.DWORD)]


class Limits(c.Structure):
    _fields_ = [("basic", Basic), ("io", c.c_ulonglong * 6),
                ("process_memory", c.c_size_t), ("job_memory", c.c_size_t),
                ("peak_process", c.c_size_t), ("peak_job", c.c_size_t)]


class Startup(c.Structure):
    _fields_ = [("size", w.DWORD), ("reserved", w.LPWSTR), ("desktop", w.LPWSTR),
                ("title", w.LPWSTR)] + [(n, w.DWORD) for n in
                ("x", "y", "width", "height", "chars_x", "chars_y", "fill", "flags")] + [
                ("show", w.WORD), ("reserved_size", w.WORD), ("reserved_data", c.c_void_p),
                ("stdin", w.HANDLE), ("stdout", w.HANDLE), ("stderr", w.HANDLE)]


class ProcessInfo(c.Structure):
    _fields_ = [("process", w.HANDLE), ("thread", w.HANDLE),
                ("pid", w.DWORD), ("tid", w.DWORD)]


def kernel():
    k = c.WinDLL("kernel32", use_last_error=True)
    for name, args, result in [
        ("CreateJobObjectW", [c.c_void_p, w.LPCWSTR], w.HANDLE),
        ("SetInformationJobObject", [w.HANDLE, c.c_int, c.c_void_p, w.DWORD], w.BOOL),
        ("AssignProcessToJobObject", [w.HANDLE, w.HANDLE], w.BOOL),
        ("TerminateJobObject", [w.HANDLE, w.UINT], w.BOOL),
        ("GetCurrentProcess", [], w.HANDLE),
        ("GetProcessAffinityMask", [w.HANDLE, c.c_void_p, c.c_void_p], w.BOOL),
        ("SetProcessAffinityMask", [w.HANDLE, c.c_size_t], w.BOOL),
        ("CreateProcessW", [w.LPCWSTR, w.LPWSTR, c.c_void_p, c.c_void_p, w.BOOL,
                            w.DWORD, c.c_void_p, w.LPCWSTR, c.c_void_p, c.c_void_p], w.BOOL),
        ("TerminateProcess", [w.HANDLE, w.UINT], w.BOOL),
        ("ResumeThread", [w.HANDLE], w.DWORD),
        ("WaitForSingleObject", [w.HANDLE, w.DWORD], w.DWORD),
        ("GetExitCodeProcess", [w.HANDLE, c.c_void_p], w.BOOL),
        ("CloseHandle", [w.HANDLE], w.BOOL),
    ]:
        fn = getattr(k, name)
        fn.argtypes, fn.restype = args, result
    return k


def checked(value):
    if not value:
        raise c.WinError(c.get_last_error())
    return value


class Child:
    def __init__(self, command, cwd, env, log, backend=False, on_line=lambda line: None,
                 memory_limit=10_000_000_000, training_memory_limit=None):
        import msvcrt
        self.k = k = kernel()
        self.job = checked(k.CreateJobObjectW(None, None))
        self.process = None
        self.reader = None
        limits = Limits()
        limits.basic.flags = 0x2000  # KILL_ON_JOB_CLOSE
        mask, system = c.c_size_t(), c.c_size_t()
        checked(k.GetProcessAffinityMask(k.GetCurrentProcess(), c.byref(mask), c.byref(system)))
        if backend:
            limits.basic.flags |= 0x10 | 0x20 | 0x200
            limits.basic.affinity = mask.value & -mask.value  # Respect an enclosing worker CPU restriction.
            limits.basic.priority = 0x40
            limits.job_memory = memory_limit
        if not backend and training_memory_limit is not None:
            limits.basic.flags |= 0x200
            limits.job_memory = training_memory_limit
        read_fd, write_fd = os.pipe()
        try:
            checked(k.SetInformationJobObject(self.job, 9, c.byref(limits), c.sizeof(limits)))
            os.set_inheritable(write_fd, True)
            info = ProcessInfo()
            startup = Startup()
            startup.size, startup.flags = c.sizeof(startup), 0x100
            with open(os.devnull, "rb") as source:
                startup.stdin = msvcrt.get_osfhandle(source.fileno())
                os.set_handle_inheritable(startup.stdin, True)
                startup.stdout = startup.stderr = msvcrt.get_osfhandle(write_fd)
                environment = c.create_unicode_buffer("\0".join(
                    f"{key}={value}" for key, value in sorted(env.items())) + "\0\0")
                checked(k.CreateProcessW(None, c.create_unicode_buffer(subprocess.list2cmdline(command)),
                    None, None, True, 0x4 | 0x08000000 | 0x400,
                    environment, str(cwd), c.byref(startup), c.byref(info)))
            self.process, self.pid = info.process, info.pid
            try:
                checked(k.AssignProcessToJobObject(self.job, self.process))
                if not backend:
                    checked(k.SetProcessAffinityMask(self.process, system.value))
                checked(k.ResumeThread(info.thread) != 0xFFFFFFFF)
            except BaseException:
                k.TerminateProcess(self.process, 125)
                raise
            finally:
                k.CloseHandle(info.thread)
            os.close(write_fd)
            write_fd = None

            def drain():
                # Never block the child on a full pipe or retain unlimited logs.
                count, pending = 0, b""
                with os.fdopen(read_fd, "rb", buffering=0) as source, log.open("wb") as target:
                    while data := source.read(4096):
                        if count + len(data) > 1_000_000:
                            target.seek(0)
                            target.truncate()
                            count = 0
                        target.write(data)
                        target.flush()
                        count += len(data)
                        pending = (pending + data)[-65536:]
                        lines = pending.split(b"\n")
                        pending = lines.pop()
                        for line in lines:
                            try:
                                on_line(line.decode("utf-8", errors="replace"))
                            except Exception:
                                pass  # Progress is optional; worker exit remains authoritative.

            self.reader = threading.Thread(target=drain, daemon=True)
            self.reader.start()
        except BaseException:
            if write_fd is not None:
                os.close(write_fd)
            os.close(read_fd)
            self.close()
            raise

    def poll(self):
        if self.k.WaitForSingleObject(self.process, 0) == 258:
            return None
        code = w.DWORD()
        checked(self.k.GetExitCodeProcess(self.process, c.byref(code)))
        return code.value

    def close(self):
        if self.job:
            self.k.CloseHandle(self.job)
            self.job = None
        if self.reader:
            self.reader.join(timeout=5)
        if self.process:
            self.k.CloseHandle(self.process)
            self.process = None
