package top.focess.veto.sandbox;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolDocs;

/** Starts one target in an AppContainer on a private desktop with permit-scoped capabilities. */
final class WindowsAppContainerLauncher {

    private static final int INFINITE = -1;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES = 0x00020009;
    private static final int STARTF_USESTDHANDLES = 0x00000100;
    private static final int STD_INPUT_HANDLE = -10;
    private static final int STD_OUTPUT_HANDLE = -11;
    private static final int STD_ERROR_HANDLE = -12;
    private static final int DESKTOP_ALL_ACCESS = 0x000F01FF;
    private static final int SE_WINDOW_OBJECT = 7;
    private static final int DACL_SECURITY_INFORMATION = 0x00000004;
    private static final int GENERIC_ALL = 0x10000000;
    private static final int GRANT_ACCESS = 1;
    private static final int TRUSTEE_IS_SID = 0;
    private static final int TRUSTEE_IS_UNKNOWN = 0;
    private static final int SE_GROUP_ENABLED = 0x00000004;
    private static final @NonNull List<@NonNull String> NETWORK_CAPABILITY_SIDS =
            List.of("S-1-15-3-1", "S-1-15-3-2", "S-1-15-3-3");

    private WindowsAppContainerLauncher() {}

    static boolean isAvailable() {
        try {
            Native.load("userenv", ToolDocs.nonNullClass(AppContainerApi.class));
            Native.load("kernel32", ToolDocs.nonNullClass(ProcessApi.class));
            Native.load("user32", ToolDocs.nonNullClass(DesktopApi.class));
            Native.load("advapi32", ToolDocs.nonNullClass(SecurityApi.class));
            return true;
        } catch (LinkageError | RuntimeException unavailable) {
            return false;
        }
    }

    static @NonNull PrivateDesktop createPrivateDesktop(@NonNull String appContainerName) {
        AppContainerApi containers =
                Native.load("userenv", ToolDocs.nonNullClass(AppContainerApi.class));
        DesktopApi desktops = Native.load("user32", ToolDocs.nonNullClass(DesktopApi.class));
        SecurityApi security = Native.load("advapi32", ToolDocs.nonNullClass(SecurityApi.class));
        PointerByReference appContainerSid = new PointerByReference();
        int derive =
                containers.DeriveAppContainerSidFromAppContainerName(
                        new WString(appContainerName), appContainerSid);
        if (derive != 0 || appContainerSid.getValue() == null) {
            throw new IllegalStateException(
                    "AppContainer SID derivation failed (HRESULT=" + derive + ")");
        }
        try {
            String desktopName = "VetoSandbox-" + UUID.randomUUID();
            WinNT.HANDLE desktop =
                    desktops.CreateDesktopW(
                            new WString(desktopName), null, null, 0, DESKTOP_ALL_ACCESS, null);
            desktop = requireHandle(desktop, "CreateDesktopW");
            try {
                grantDesktopAccess(security, desktop, appContainerSid.getValue());
                return new PrivateDesktop(desktopName, desktop, desktops);
            } catch (RuntimeException failure) {
                desktops.CloseDesktop(desktop);
                throw failure;
            }
        } finally {
            security.FreeSid(appContainerSid.getValue());
        }
    }

    static WinNT.@NonNull HANDLE openPrivateDesktop(@NonNull String desktopName) {
        DesktopApi desktops = Native.load("user32", ToolDocs.nonNullClass(DesktopApi.class));
        return requireHandle(
                desktops.OpenDesktopW(new WString(desktopName), 0, false, DESKTOP_ALL_ACCESS),
                "OpenDesktopW");
    }

    static void closePrivateDesktop(WinNT.@NonNull HANDLE desktop) {
        DesktopApi desktops = Native.load("user32", ToolDocs.nonNullClass(DesktopApi.class));
        desktops.CloseDesktop(desktop);
    }

    static int run(
            @NonNull List<@NonNull String> target,
            @NonNull String appContainerName,
            @NonNull String desktopName,
            boolean networkAllowed) {
        if (target.isEmpty()) {
            System.err.println("AppContainer target command is empty");
            return 125;
        }
        AppContainerApi containers =
                Native.load("userenv", ToolDocs.nonNullClass(AppContainerApi.class));
        ProcessApi processes = Native.load("kernel32", ToolDocs.nonNullClass(ProcessApi.class));
        SecurityApi security = Native.load("advapi32", ToolDocs.nonNullClass(SecurityApi.class));
        PointerByReference appContainerSid = new PointerByReference();
        int derive =
                containers.DeriveAppContainerSidFromAppContainerName(
                        new WString(appContainerName), appContainerSid);
        if (derive != 0 || appContainerSid.getValue() == null) {
            System.err.println("AppContainer SID derivation failed (HRESULT=" + derive + ")");
            return 125;
        }

        Memory attributeList = null;
        List<Pointer> capabilitySids = new ArrayList<>();
        WinBase.PROCESS_INFORMATION child = new WinBase.PROCESS_INFORMATION();
        boolean childThreadClosed = false;
        try {
            Memory requiredSize = new Memory(Native.POINTER_SIZE);
            requiredSize.clear();
            processes.InitializeProcThreadAttributeList(null, 1, 0, requiredSize);
            long bytes = readSizeT(requiredSize);
            if (bytes <= 0) {
                return nativeFailure("InitializeProcThreadAttributeList(size)");
            }
            attributeList = new Memory(bytes);
            if (!processes.InitializeProcThreadAttributeList(attributeList, 1, 0, requiredSize)) {
                return nativeFailure("InitializeProcThreadAttributeList");
            }

            SecurityCapabilities capabilities = new SecurityCapabilities();
            capabilities.appContainerSid = appContainerSid.getValue();
            if (networkAllowed) {
                SidAndAttributes[] capabilityEntries =
                        (SidAndAttributes[])
                                new SidAndAttributes().toArray(NETWORK_CAPABILITY_SIDS.size());
                for (int i = 0; i < NETWORK_CAPABILITY_SIDS.size(); i++) {
                    PointerByReference capabilitySid = new PointerByReference();
                    if (!security.ConvertStringSidToSidW(
                                    new WString(NETWORK_CAPABILITY_SIDS.get(i)), capabilitySid)
                            || capabilitySid.getValue() == null) {
                        return nativeFailure("ConvertStringSidToSidW(network capability)");
                    }
                    capabilitySids.add(capabilitySid.getValue());
                    capabilityEntries[i].sid = capabilitySid.getValue();
                    capabilityEntries[i].attributes = SE_GROUP_ENABLED;
                    capabilityEntries[i].write();
                }
                capabilities.capabilities = capabilityEntries[0].getPointer();
                capabilities.capabilityCount = capabilityEntries.length;
            } else {
                capabilities.capabilities = null;
                capabilities.capabilityCount = 0;
            }
            capabilities.reserved = 0;
            capabilities.write();
            if (!processes.UpdateProcThreadAttribute(
                    attributeList,
                    0,
                    new BaseTSD.ULONG_PTR(PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES),
                    capabilities.getPointer(),
                    new BaseTSD.SIZE_T(capabilities.size()),
                    null,
                    null)) {
                return nativeFailure("UpdateProcThreadAttribute(SecurityCapabilities)");
            }

            StartupInfoEx startup = inheritedStandardHandles(attributeList, desktopName);
            char[] commandLine = nullTerminated(SandboxBootstrap.windowsCommandLine(target));
            if (!processes.CreateProcessW(
                    new WString(target.get(0)),
                    commandLine,
                    null,
                    null,
                    true,
                    EXTENDED_STARTUPINFO_PRESENT,
                    null,
                    null,
                    startup,
                    child)) {
                return nativeFailure("CreateProcessW(AppContainer)");
            }
            Kernel32.INSTANCE.CloseHandle(child.hThread);
            childThreadClosed = true;
            int wait = Kernel32.INSTANCE.WaitForSingleObject(child.hProcess, INFINITE);
            if (wait != WAIT_OBJECT_0) {
                System.err.println("AppContainer child wait failed: " + wait);
                return 125;
            }
            IntByReference exitCode = new IntByReference();
            if (!Kernel32.INSTANCE.GetExitCodeProcess(child.hProcess, exitCode)) {
                return nativeFailure("GetExitCodeProcess");
            }
            return exitCode.getValue();
        } finally {
            if (!childThreadClosed && child.hThread != null) {
                Kernel32.INSTANCE.CloseHandle(child.hThread);
            }
            if (child.hProcess != null) {
                Kernel32.INSTANCE.CloseHandle(child.hProcess);
            }
            if (attributeList != null) {
                processes.DeleteProcThreadAttributeList(attributeList);
            }
            for (Pointer capabilitySid : capabilitySids) {
                localFree(capabilitySid);
            }
            security.FreeSid(appContainerSid.getValue());
        }
    }

    private static void grantDesktopAccess(
            @NonNull SecurityApi security, WinNT.@NonNull HANDLE desktop, @NonNull Pointer sid) {
        PointerByReference oldAcl = new PointerByReference();
        PointerByReference descriptor = new PointerByReference();
        PointerByReference newAcl = new PointerByReference();
        int getResult =
                security.GetSecurityInfo(
                        desktop,
                        SE_WINDOW_OBJECT,
                        DACL_SECURITY_INFORMATION,
                        null,
                        null,
                        oldAcl,
                        null,
                        descriptor);
        if (getResult != 0) {
            throw new IllegalStateException(
                    "GetSecurityInfo(desktop) failed (Win32 error=" + getResult + ")");
        }
        try {
            WindowsWorkspaceSecurity.ExplicitAccess access =
                    WindowsWorkspaceSecurity.explicitSidAccess(sid, GENERIC_ALL, GRANT_ACCESS, 0);
            int aclResult = security.SetEntriesInAclW(1, access, oldAcl.getValue(), newAcl);
            if (aclResult != 0) {
                throw new IllegalStateException(
                        "SetEntriesInAclW(desktop) failed (Win32 error=" + aclResult + ")");
            }
            int setResult =
                    security.SetSecurityInfo(
                            desktop,
                            SE_WINDOW_OBJECT,
                            DACL_SECURITY_INFORMATION,
                            null,
                            null,
                            newAcl.getValue(),
                            null);
            if (setResult != 0) {
                throw new IllegalStateException(
                        "SetSecurityInfo(desktop) failed (Win32 error=" + setResult + ")");
            }
        } finally {
            localFree(newAcl.getValue());
            localFree(descriptor.getValue());
        }
    }

    private static @NonNull StartupInfoEx inheritedStandardHandles(
            @NonNull Pointer attributes, @NonNull String desktopName) {
        StartupInfoEx startup = new StartupInfoEx();
        startup.startupInfo.cb = new WinDef.DWORD(startup.size());
        startup.startupInfo.dwFlags = STARTF_USESTDHANDLES;
        startup.startupInfo.lpDesktop = desktopName;
        startup.startupInfo.hStdInput = Kernel32.INSTANCE.GetStdHandle(STD_INPUT_HANDLE);
        startup.startupInfo.hStdOutput = Kernel32.INSTANCE.GetStdHandle(STD_OUTPUT_HANDLE);
        startup.startupInfo.hStdError = Kernel32.INSTANCE.GetStdHandle(STD_ERROR_HANDLE);
        startup.attributeList = attributes;
        startup.write();
        return startup;
    }

    private static long readSizeT(@NonNull Memory memory) {
        return Native.POINTER_SIZE == Long.BYTES
                ? memory.getLong(0)
                : Integer.toUnsignedLong(memory.getInt(0));
    }

    private static boolean validHandle(WinNT.@NonNull HANDLE handle) {
        if (handle.getPointer() == null) {
            return false;
        }
        long value = Pointer.nativeValue(handle.getPointer());
        return value != 0 && value != -1L;
    }

    private static WinNT.@NonNull HANDLE requireHandle(
            WinNT.HANDLE handle, @NonNull String operation) {
        if (handle == null || !validHandle(handle)) {
            throw new IllegalStateException(operation + " returned an invalid handle");
        }
        return handle;
    }

    private static char @NonNull [] nullTerminated(@NonNull String value) {
        return (value + '\0').toCharArray();
    }

    private static void localFree(Pointer pointer) {
        if (pointer != null) {
            Kernel32.INSTANCE.LocalFree(pointer);
        }
    }

    private static int nativeFailure(@NonNull String operation) {
        System.err.println(
                operation + " failed (Win32 error=" + Kernel32.INSTANCE.GetLastError() + ")");
        return 125;
    }

    interface AppContainerApi extends StdCallLibrary {
        int DeriveAppContainerSidFromAppContainerName(
                WString appContainerName, PointerByReference appContainerSid);
    }

    interface ProcessApi extends Library {
        boolean InitializeProcThreadAttributeList(
                Pointer attributeList, int attributeCount, int flags, Pointer size);

        boolean UpdateProcThreadAttribute(
                Pointer attributeList,
                int flags,
                BaseTSD.ULONG_PTR attribute,
                Pointer value,
                BaseTSD.SIZE_T size,
                Pointer previousValue,
                Pointer returnSize);

        void DeleteProcThreadAttributeList(Pointer attributeList);

        boolean CreateProcessW(
                WString applicationName,
                char[] commandLine,
                WinBase.SECURITY_ATTRIBUTES processAttributes,
                WinBase.SECURITY_ATTRIBUTES threadAttributes,
                boolean inheritHandles,
                int creationFlags,
                Pointer environment,
                WString currentDirectory,
                StartupInfoEx startupInfo,
                WinBase.PROCESS_INFORMATION processInformation);
    }

    interface DesktopApi extends StdCallLibrary {
        WinNT.HANDLE CreateDesktopW(
                WString desktop,
                WString device,
                Pointer deviceMode,
                int flags,
                int desiredAccess,
                WinBase.SECURITY_ATTRIBUTES securityAttributes);

        WinNT.HANDLE OpenDesktopW(WString desktop, int flags, boolean inherit, int desiredAccess);

        boolean CloseDesktop(WinNT.HANDLE desktop);
    }

    interface SecurityApi extends StdCallLibrary {
        Pointer FreeSid(Pointer sid);

        boolean ConvertStringSidToSidW(WString stringSid, PointerByReference sid);

        int GetSecurityInfo(
                WinNT.HANDLE handle,
                int objectType,
                int securityInfo,
                PointerByReference owner,
                PointerByReference group,
                PointerByReference dacl,
                PointerByReference sacl,
                PointerByReference securityDescriptor);

        int SetEntriesInAclW(
                int explicitEntries,
                WindowsWorkspaceSecurity.ExplicitAccess explicitAccess,
                Pointer oldAcl,
                PointerByReference newAcl);

        int SetSecurityInfo(
                WinNT.HANDLE handle,
                int objectType,
                int securityInfo,
                Pointer owner,
                Pointer group,
                Pointer dacl,
                Pointer sacl);
    }

    @Structure.FieldOrder({"appContainerSid", "capabilities", "capabilityCount", "reserved"})
    public static class SecurityCapabilities extends Structure {
        public Pointer appContainerSid;
        public Pointer capabilities;
        public int capabilityCount;
        public int reserved;
    }

    @Structure.FieldOrder({"sid", "attributes"})
    public static class SidAndAttributes extends Structure {
        public Pointer sid;
        public int attributes;
    }

    @Structure.FieldOrder({"startupInfo", "attributeList"})
    public static class StartupInfoEx extends Structure {
        public WinBase.@NonNull STARTUPINFO startupInfo = new WinBase.STARTUPINFO();
        public Pointer attributeList;
    }

    static final class PrivateDesktop implements AutoCloseable {
        private final @NonNull String name;
        private final WinNT.@NonNull HANDLE handle;
        private final @NonNull DesktopApi api;

        PrivateDesktop(
                @NonNull String name, WinNT.@NonNull HANDLE handle, @NonNull DesktopApi api) {
            this.name = name;
            this.handle = handle;
            this.api = api;
        }

        @NonNull String name() {
            return name;
        }

        @Override
        public void close() {
            api.CloseDesktop(handle);
        }
    }
}
