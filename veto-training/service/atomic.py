"""Atomic file replacement with bounded retries for transient Windows file sharing conflicts."""
import time


def replace_with_retry(source, target):
    deadline = time.monotonic() + 10
    delay = 0.05
    while True:
        try:
            source.replace(target)
            return
        except PermissionError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(delay)
            delay = min(delay * 2, 0.5)
