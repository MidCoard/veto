package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.util.ArrayDeque;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import top.focess.veto.sandbox.KernelSandboxSubstrate.JobObjectBasicAccountingInformation;
import top.focess.veto.sandbox.KernelSandboxSubstrate.WindowsKernel32;

/** Deterministic native races complement the real detached-tree integration test. */
@EnabledOnOs(OS.WINDOWS)
class WindowsJobLeaseTest {
    private final @NonNull WindowsKernel32 kernel = mock(WindowsKernel32.class);
    private final @NonNull Kernel32 standard = mock(Kernel32.class);
    private final WinNT.@NonNull HANDLE job = new WinNT.HANDLE(Pointer.createConstant(100));
    private final WinNT.@NonNull HANDLE port = new WinNT.HANDLE(Pointer.createConstant(101));
    private final WinNT.@NonNull HANDLE parent = new WinNT.HANDLE(Pointer.createConstant(102));
    private final WinNT.@NonNull HANDLE child = new WinNT.HANDLE(Pointer.createConstant(103));
    private final @NonNull ArrayDeque<@NonNull Integer> notifications = new ArrayDeque<>();
    private int total = 1;

    @BeforeEach
    void nativeApi() {
        when(kernel.TerminateJobObject(job, 1)).thenReturn(true);
        when(standard.CloseHandle(any())).thenReturn(true);
        when(kernel.OpenProcess(WinNT.SYNCHRONIZE, false, 1)).thenReturn(parent);
        when(kernel.OpenProcess(WinNT.SYNCHRONIZE, false, 2)).thenReturn(child);
        when(standard.GetQueuedCompletionStatus(eq(port), any(), any(), any(), anyInt()))
                .thenAnswer(
                        call -> {
                            var pid = notifications.poll();
                            if (pid == null) return false;
                            call.<IntByReference>getArgument(1).setValue(6);
                            call.<PointerByReference>getArgument(3)
                                    .setValue(Pointer.createConstant(pid));
                            return true;
                        });
        when(kernel.QueryInformationJobObject(eq(job), eq(1), any(), anyInt(), isNull()))
                .thenAnswer(
                        call -> {
                            var accounting =
                                    call.<JobObjectBasicAccountingInformation>getArgument(2);
                            accounting.TotalProcesses = total;
                            accounting.ActiveProcesses = 0;
                            accounting.write();
                            return true;
                        });
    }

    @Test
    void waitsForDescendantAddedDuringRetirementDespiteZeroActiveCount() {
        notifications.add(1);
        when(kernel.WaitForSingleObject(eq(parent), anyInt()))
                .thenAnswer(
                        call -> {
                            total++;
                            notifications.add(2);
                            return 0;
                        });
        new WindowsJobLease(kernel, standard, job, port, 1).close();
        var order = inOrder(kernel, standard);
        order.verify(kernel).TerminateJobObject(job, 1);
        order.verify(kernel).WaitForSingleObject(eq(parent), anyInt());
        order.verify(standard).CloseHandle(parent);
        order.verify(kernel).WaitForSingleObject(eq(child), anyInt());
        order.verify(standard).CloseHandle(child);
        order.verify(standard).CloseHandle(port);
        order.verify(standard).CloseHandle(job);
    }

    @Test
    void lostNotificationCannotTurnZeroActiveCountIntoSuccess() {
        notifications.add(1);
        total = 2;
        when(standard.GetLastError()).thenReturn(WinError.WAIT_TIMEOUT);
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> new WindowsJobLease(kernel, standard, job, port, 1).close());
        assertEquals(
                "Job retirement notifications incomplete after 1 processes: 258",
                failure.getMessage());
        verify(standard).CloseHandle(parent);
        verify(standard).CloseHandle(port);
        verify(standard).CloseHandle(job);
    }

    @Test
    void waitsForRemainingProcessesWhenAnEarlierPidHasAlreadyDisappeared() {
        notifications.addAll(List.of(1, 2));
        total = 2;
        when(kernel.OpenProcess(WinNT.SYNCHRONIZE, false, 1)).thenReturn(null);
        when(standard.GetLastError()).thenReturn(WinError.ERROR_INVALID_PARAMETER);
        new WindowsJobLease(kernel, standard, job, port, 1).close();
        verify(kernel, never()).WaitForSingleObject(eq(parent), anyInt());
        verify(kernel).WaitForSingleObject(eq(child), anyInt());
    }

    @Test
    void accessDenialIsNotMistakenForAnExitedProcess() {
        notifications.add(1);
        when(kernel.OpenProcess(WinNT.SYNCHRONIZE, false, 1)).thenReturn(null);
        when(standard.GetLastError()).thenReturn(WinError.ERROR_ACCESS_DENIED);
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> new WindowsJobLease(kernel, standard, job, port, 1).close());
        assertEquals("OpenProcess for retirement failed: 5", failure.getMessage());
        verify(standard).CloseHandle(port);
        verify(standard).CloseHandle(job);
    }

    @Test
    void nativeWaitFailureClosesEveryHandleAndPreservesCleanupFailures() {
        notifications.add(1);
        when(kernel.WaitForSingleObject(eq(parent), anyInt())).thenReturn(WinError.WAIT_TIMEOUT);
        when(standard.CloseHandle(any())).thenReturn(false);
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> new WindowsJobLease(kernel, standard, job, port, 1).close());
        assertEquals("Native process retirement wait failed: result=258", failure.getMessage());
        assertEquals(3, failure.getSuppressed().length);
        verify(standard).CloseHandle(parent);
        verify(standard).CloseHandle(port);
        verify(standard).CloseHandle(job);
        verify(kernel, never()).QueryInformationJobObject(any(), anyInt(), any(), anyInt(), any());
    }
}
