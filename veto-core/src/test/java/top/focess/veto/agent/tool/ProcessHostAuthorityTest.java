package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.integration.plugins.ProcessHostFixture;
import top.focess.veto.sandbox.ConstrainedSubprocessSubstrate;
import top.focess.veto.sandbox.SandboxHandle;
import top.focess.veto.sandbox.SandboxProfile;
import top.focess.veto.sandbox.TestSandboxFactory;

class ProcessHostAuthorityTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedLaunchStopsChildBeforeRetirementAndPreservesFailure(
            boolean revokeGrant, @TempDir @NonNull Path directory) {
        var substrate = mock(ConstrainedSubprocessSubstrate.class);
        var process = mock(Process.class);
        when(process.descendants()).thenAnswer(ignored -> Stream.empty());
        when(substrate.provision(any(SandboxProfile.class)))
                .thenAnswer(
                        call ->
                                new SandboxHandle(
                                        "launch", new Object(), directory, call.getArgument(0)));
        var callbackFailure = new AssertionError("Exit callback installation failed");
        var retirementFailure = new IllegalStateException("Retirement failed");
        when(substrate.onExit(any(), same(process))).thenThrow(callbackFailure);
        doThrow(retirementFailure).when(substrate).deprovision(any());
        try (var fixture = new ProcessHostFixture(substrate, List.of(), true)) {
            when(substrate.startBackground(any(), any(), any()))
                    .thenAnswer(
                            ignored -> {
                                if (revokeGrant) fixture.admitted.set(false);
                                return process;
                            });
            var call =
                    new ToolCall(
                            "run_task",
                            Map.of(
                                    "commands",
                                    List.of(
                                            Map.of(
                                                    "executable",
                                                    javaExecutable(),
                                                    "args",
                                                    List.of("-version"))),
                                    "timeout",
                                    30),
                            "failed-start");
            bind(
                    fixture,
                    fixture.permit(
                            call,
                            definition(fixture, "run_task"),
                            Workspace.single(directory, PathMode.REAL)));
            Throwable failure;
            if (revokeGrant) {
                failure = assertThrows(SecurityException.class, fixture.host::startApproved);
                verify(substrate, never()).onExit(any(), any());
            } else {
                failure = assertThrows(AssertionError.class, fixture.host::startApproved);
                assertSame(callbackFailure, failure);
            }
            assertArrayEquals(new Throwable[] {retirementFailure}, failure.getSuppressed());
            var cleanup = inOrder(process, substrate);
            cleanup.verify(process).destroyForcibly();
            cleanup.verify(substrate).deprovision(any());
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    private static @NonNull ToolDefinition definition(
            @NonNull ProcessHostFixture fixture, @NonNull String name) {
        var value = fixture.engine.resolveDefinition(name);
        if (value == null) throw new AssertionError(name);
        return value;
    }

    private static void bind(
            @NonNull ProcessHostFixture fixture, @NonNull ToolExecutionPermit permit) {
        ToolCallContextHolder.set(
                new ToolCallContext(
                        fixture.agent,
                        fixture.userId,
                        fixture.session,
                        ToolResultPresentationMode.BASIC,
                        permit));
        ToolCallContextHolder.setCurrentCallId(permit.callId());
    }

    private static @NonNull String javaExecutable() {
        return Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").toLowerCase().contains("win")
                                ? "java.exe"
                                : "java")
                .toString();
    }

    @Test
    void approvedCommandIsOneShotAndPreparationHasNoEffectContext(
            @TempDir @NonNull Path directory) {
        try (var fixture =
                new ProcessHostFixture(
                        TestSandboxFactory.uncontainedSubprocesses(), List.of(), false)) {
            var call =
                    new ToolCall(
                            "run_command",
                            Map.of(
                                    "commands",
                                    List.of(
                                            Map.of(
                                                    "executable",
                                                    javaExecutable(),
                                                    "args",
                                                    List.of("-version"))),
                                    "timeout",
                                    30),
                            "once");
            var permit =
                    fixture.permit(
                            call,
                            definition(fixture, "run_command"),
                            Workspace.single(directory, PathMode.REAL));
            bind(fixture, permit);
            assertThrows(
                    SecurityException.class,
                    () -> ToolCallContextHolder.withoutEffects(() -> fixture.host.runApproved()));
            fixture.host.runApproved();
            assertThrows(SecurityException.class, () -> fixture.host.runApproved());
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void deferredInputIsExactOneShotAndRevocationCheckedAtDelivery(@TempDir @NonNull Path directory)
            throws Exception {
        var source = directory.resolve("Input.java");
        Files.writeString(
                source,
                "class Input { public static void main(String[] a) throws Exception {"
                        + " System.out.write(System.in.readAllBytes()); } }");
        try (var fixture =
                new ProcessHostFixture(
                        TestSandboxFactory.uncontainedSubprocesses(), List.of(), true)) {
            var call =
                    new ToolCall(
                            "run_task",
                            Map.of(
                                    "commands",
                                    List.of(
                                            Map.of(
                                                    "executable",
                                                    javaExecutable(),
                                                    "args",
                                                    List.of(source.toString()))),
                                    "timeout",
                                    30),
                            "start");
            bind(
                    fixture,
                    fixture.permit(
                            call,
                            definition(fixture, "run_task"),
                            Workspace.single(directory, PathMode.REAL)));
            var running = fixture.host.startApproved();
            try {
                assertEquals(fixture.userId, running.invocation().userId());
                var input =
                        new ToolCall(
                                "input_task",
                                Map.of("taskId", "fixture", "input", "hello", "closeStdin", true),
                                "input");
                var intent =
                        new ToolPreparation.InputIntent(
                                running, "hello".getBytes(StandardCharsets.UTF_8), true);
                var prepared =
                        new PreparedInvocation(
                                fixture.plugin,
                                new PluginHost.Invocation(
                                        fixture.userId,
                                        fixture.session.toString(),
                                        fixture.agent,
                                        null,
                                        input.callId()),
                                input,
                                new ToolPreparation(intent, new JsonValue.ObjectValue(Map.of())),
                                ToolCapability.PROCESS_EXECUTION);
                assertFalse(prepared.facts().contains("aGVsbG8="));
                assertFalse(prepared.facts().contains("hello"));
                var permit =
                        ToolExecutionPermit.capture(
                                        input,
                                        definition(fixture, "input_task"),
                                        Workspace.single(directory, PathMode.REAL))
                                .withCaller(fixture.agent, fixture.userId, fixture.session)
                                .withPreparation(prepared);
                bind(fixture, permit);
                var write = fixture.host.prepareInput(running);
                ToolCallContextHolder.clear();
                assertEquals(5, write.byteCount());
                write.write();
                assertThrows(SecurityException.class, write::write);
                assertTrue(running.awaitExit(Duration.ofSeconds(20)));
                String output = new String(running.output().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(output.endsWith("hello"), output);
                assertEquals(output.indexOf("hello"), output.lastIndexOf("hello"), output);
            } finally {
                running.close();
            }
            bind(
                    fixture,
                    fixture.permit(
                            new ToolCall(call.toolName(), call.args(), "second"),
                            definition(fixture, "run_task"),
                            Workspace.single(directory, PathMode.REAL)));
            var second = fixture.host.startApproved();
            try {
                var input = new ToolCall("input_task", Map.of(), "revoked");
                var prepared =
                        new PreparedInvocation(
                                fixture.plugin,
                                new PluginHost.Invocation(
                                        fixture.userId,
                                        fixture.session.toString(),
                                        fixture.agent,
                                        null,
                                        input.callId()),
                                input,
                                new ToolPreparation(
                                        new ToolPreparation.InputIntent(
                                                second, new byte[] {1}, false),
                                        new JsonValue.ObjectValue(Map.of())),
                                ToolCapability.PROCESS_EXECUTION);
                bind(
                        fixture,
                        ToolExecutionPermit.capture(
                                        input,
                                        definition(fixture, "input_task"),
                                        Workspace.single(directory, PathMode.REAL))
                                .withCaller(fixture.agent, fixture.userId, fixture.session)
                                .withPreparation(prepared));
                var write = fixture.host.prepareInput(second);
                var retainedOutput = second.output();
                ToolCallContextHolder.clear();
                fixture.admitted.set(false);
                assertThrows(IOException.class, retainedOutput::available);
                assertThrows(SecurityException.class, write::write);
                fixture.admitted.set(true);
            } finally {
                second.close();
                // Windows retains the process working directory until the child has exited.
                assertTrue(second.awaitExit(Duration.ofSeconds(20)));
            }
        } finally {
            ToolCallContextHolder.clear();
        }
    }
}
