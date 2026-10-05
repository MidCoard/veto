package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolInvocationFixture;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.process.Command;
import top.focess.veto.integration.plugins.ProcessHostFixture;

/** Native exit completion must precede retirement of temporary filesystem exclusions. */
class ProcessTreeRetirementTest {
    @Test
    @Timeout(60)
    @EnabledOnOs(OS.WINDOWS)
    void cancellationStopsTheTreeBeforeFilesystemRetirement(@TempDir @NonNull Path workspace)
            throws Exception {
        var marker = workspace.resolve("running.pid");
        var script = workspace.resolve("running.ps1");
        Files.writeString(
                script,
                "[IO.File]::WriteAllText('"
                        + marker.toString().replace("'", "''")
                        + "', [string]$PID); while ($true) { [Threading.Thread]::Sleep(50) }");
        var substrate = new ConstrainedSubprocessSubstrate(new KernelSandboxSubstrate());
        try (var fixture = new ProcessHostFixture(substrate, List.of(), false)) {
            var definition = fixture.engine.resolveDefinition("run_command");
            if (definition == null) throw new AssertionError("missing run_command definition");
            var call =
                    new ToolCall(
                            "run_command",
                            Map.of(
                                    "commands",
                                    List.of(
                                            Map.of(
                                                    "executable",
                                                    "powershell.exe",
                                                    "args",
                                                    List.of(
                                                            "-NoProfile",
                                                            "-NonInteractive",
                                                            "-File",
                                                            script.toString()))),
                                    "timeout",
                                    30),
                            "cancel");
            var permit =
                    fixture.permit(call, definition, Workspace.single(workspace, PathMode.REAL));
            var finished = new CompletableFuture<Void>();
            var caller =
                    Thread.ofPlatform()
                            .daemon(true)
                            .start(
                                    () -> {
                                        try {
                                            ToolCallContextHolder.set(
                                                    new ToolCallContext(
                                                            fixture.agent,
                                                            fixture.user,
                                                            fixture.owner,
                                                            fixture.session,
                                                            ToolResultPresentationMode.BASIC,
                                                            permit));
                                            var result =
                                                    ToolInvocationFixture.call(
                                                            permit.callId(),
                                                            fixture.host::runApproved);
                                            assertEquals(-1, result.exitCode());
                                            if (!finished.complete(null))
                                                throw new AssertionError("duplicate completion");
                                        } catch (Throwable failure) {
                                            if (!finished.completeExceptionally(failure))
                                                throw new AssertionError("duplicate failure");
                                        } finally {
                                            ToolCallContextHolder.clear();
                                        }
                                    });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!Files.exists(marker)) {
                    if (System.nanoTime() >= deadline) fail("contained process did not start");
                    Thread.sleep(20);
                }
                caller.interrupt();
                finished.get(30, TimeUnit.SECONDS);
                long targetId = Long.parseLong(Files.readString(marker));
                assertFalse(ProcessHandle.of(targetId).map(ProcessHandle::isAlive).orElse(false));
            } finally {
                caller.interrupt();
            }
        }
    }

    @Test
    @Timeout(60)
    @EnabledOnOs(OS.WINDOWS)
    void concurrentRetirementWaitsForAnAlreadyAdmittedAttachment(@TempDir @NonNull Path workspace)
            throws Exception {
        var kernel = spy(new KernelSandboxSubstrate());
        var attachmentEntered = new CountDownLatch(1);
        var releaseAttachment = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            attachmentEntered.countDown();
                            if (!releaseAttachment.await(20, TimeUnit.SECONDS))
                                throw new IllegalStateException("test did not release attachment");
                            return call.callRealMethod();
                        })
                .when(kernel)
                .attach(any(), any());
        var substrate = new ConstrainedSubprocessSubstrate(kernel);
        var handle =
                substrate.provision(
                        new SandboxProfile(workspace, 512, 100, 8, Duration.ofSeconds(30)));
        var start =
                CompletableFuture.supplyAsync(
                        () ->
                                substrate.startBackground(
                                        handle,
                                        new Command("cmd.exe", List.of("/c", "echo", "admitted")),
                                        workspace));
        try {
            assertTrue(attachmentEntered.await(20, TimeUnit.SECONDS));
            var retirementStarted = new CountDownLatch(1);
            var retired =
                    CompletableFuture.runAsync(
                            () -> {
                                retirementStarted.countDown();
                                substrate.deprovision(handle);
                            });
            assertTrue(retirementStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> retired.get(200, TimeUnit.MILLISECONDS));
            verify(kernel, never()).deprovisionWorkspace(any());
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            substrate.startBackground(
                                    handle,
                                    new Command("cmd.exe", List.of("/c", "echo", "too-late")),
                                    workspace));
            releaseAttachment.countDown();
            var process = start.get(30, TimeUnit.SECONDS);
            retired.get(30, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            verify(kernel).deprovisionWorkspace(handle.profile());
        } finally {
            releaseAttachment.countDown();
            start.thenAccept(Process::destroyForcibly);
        }
    }

    @Test
    @Timeout(60)
    @EnabledOnOs(OS.WINDOWS)
    void failedNativeRetirementKeepsFilesystemExclusions(@TempDir @NonNull Path workspace)
            throws Exception {
        var kernel = spy(new KernelSandboxSubstrate());
        doAnswer(
                        call -> {
                            if (!(call.callRealMethod() instanceof CompletableFuture<?> nativeExit))
                                throw new IllegalStateException(
                                        "attachment did not publish retirement");
                            return nativeExit.thenRun(
                                    () -> {
                                        throw new IllegalStateException(
                                                "native accounting proof unavailable");
                                    });
                        })
                .when(kernel)
                .attach(any(), any());
        var substrate = new ConstrainedSubprocessSubstrate(kernel);
        var handle =
                substrate.provision(
                        new SandboxProfile(workspace, 512, 100, 8, Duration.ofSeconds(30)));
        var process =
                substrate.startBackground(
                        handle,
                        new Command("cmd.exe", List.of("/c", "echo", "retirement-failure")),
                        workspace);
        try {
            assertThrows(
                    ExecutionException.class,
                    () -> substrate.onExit(handle, process).get(30, TimeUnit.SECONDS));
            assertThrows(CompletionException.class, () -> substrate.deprovision(handle));
            verify(kernel, never()).deprovisionWorkspace(any());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    @Timeout(60)
    @EnabledOnOs(OS.WINDOWS)
    void parentExitRetiresItsDetachedGrandchildBeforeDeprovision(@TempDir @NonNull Path workspace)
            throws Exception {
        var childScript = workspace.resolve("child.ps1");
        var marker = workspace.resolve("child.pid");
        Files.writeString(
                childScript,
                "[IO.File]::WriteAllText('"
                        + marker.toString().replace("'", "''")
                        + "', [string]$PID); while ($true) { [Threading.Thread]::Sleep(50) }");
        var parentScript = workspace.resolve("parent.ps1");
        Files.writeString(
                parentScript,
                """
                $info = [Diagnostics.ProcessStartInfo]::new()
                $info.FileName = (Get-Process -Id $PID).Path
                $info.UseShellExecute = $false
                $info.RedirectStandardOutput = $true
                $info.RedirectStandardError = $true
                $info.Arguments = '-NoProfile -NonInteractive -File "CHILD_SCRIPT"'
                $child = [Diagnostics.Process]::Start($info)
                $deadline = [DateTime]::UtcNow.AddSeconds(20)
                while (!(Test-Path 'CHILD_MARKER')) {
                    if ([DateTime]::UtcNow -gt $deadline -or $child.HasExited) { exit 2 }
                    [Threading.Thread]::Sleep(20)
                }
                [Console]::Out.WriteLine('parent-exiting')
                exit 0
                """
                        .replace("CHILD_SCRIPT", childScript.toString().replace("'", "''"))
                        .replace("CHILD_MARKER", marker.toString().replace("'", "''")));
        var substrate = new ConstrainedSubprocessSubstrate(new KernelSandboxSubstrate());
        var handle =
                substrate.provision(
                        new SandboxProfile(workspace, 512, 100, 8, Duration.ofSeconds(30)));
        var process =
                substrate.startBackground(
                        handle,
                        new Command(
                                "powershell.exe",
                                List.of(
                                        "-NoProfile",
                                        "-NonInteractive",
                                        "-File",
                                        parentScript.toString())),
                        workspace);
        try {
            substrate
                    .onExit(handle, process)
                    .thenRun(() -> substrate.deprovision(handle))
                    .get(40, TimeUnit.SECONDS);
            assertEquals(0, process.exitValue());
            long childId = Long.parseLong(Files.readString(marker));
            assertFalse(
                    ProcessHandle.of(childId).map(ProcessHandle::isAlive).orElse(false),
                    "ACL retirement must wait until the detached grandchild stops");
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            substrate.startBackground(
                                    handle,
                                    new Command("cmd.exe", List.of("/c", "echo", "retired")),
                                    workspace));
        } finally {
            process.destroyForcibly();
            substrate.deprovision(handle);
        }
    }
}
