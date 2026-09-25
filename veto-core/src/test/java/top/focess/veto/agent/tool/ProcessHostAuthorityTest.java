package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.integration.plugins.ProcessHostFixture;
import top.focess.veto.sandbox.TestSandboxFactory;

class ProcessHostAuthorityTest {
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
                        fixture.user,
                        fixture.owner,
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
                    ToolDocs.nonNullClass(SecurityException.class),
                    () -> ToolCallContextHolder.withoutEffects(() -> fixture.host.runApproved()));
            fixture.host.runApproved();
            assertThrows(
                    ToolDocs.nonNullClass(SecurityException.class),
                    () -> fixture.host.runApproved());
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
                "class Input { public static void main(String[] a) throws Exception { System.out.write(System.in.readAllBytes()); } }");
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
                                        fixture.owner,
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
                                .withCaller(
                                        fixture.agent, fixture.user, fixture.owner, fixture.session)
                                .withPreparation(prepared);
                bind(fixture, permit);
                var write = fixture.host.prepareInput(running);
                ToolCallContextHolder.clear();
                assertEquals(5, write.byteCount());
                write.write();
                assertThrows(ToolDocs.nonNullClass(SecurityException.class), write::write);
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
                                        fixture.owner,
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
                                .withCaller(
                                        fixture.agent, fixture.user, fixture.owner, fixture.session)
                                .withPreparation(prepared));
                var write = fixture.host.prepareInput(second);
                var retainedOutput = second.output();
                ToolCallContextHolder.clear();
                fixture.admitted.set(false);
                assertThrows(ToolDocs.nonNullClass(IOException.class), retainedOutput::available);
                assertThrows(ToolDocs.nonNullClass(SecurityException.class), write::write);
                fixture.admitted.set(true);
            } finally {
                second.close();
            }
        } finally {
            ToolCallContextHolder.clear();
        }
    }
}
