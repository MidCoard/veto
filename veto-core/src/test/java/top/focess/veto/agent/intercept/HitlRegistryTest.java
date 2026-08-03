package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import top.focess.veto.llm.core.ToolCall;

/**
 * Tests for {@link HitlRegistry}'s park/resolve path after the structural stash fix: {@code
 * register} stashes the call/def/offered-options so the transport resolves a veto by option name
 * alone, and {@code resolveOption}/{@code declineOption} build the {@link InterceptResolution} from
 * the stash (the transport cannot reach a {@link top.focess.veto.agent.mcp.ToolDefinition}).
 */
class HitlRegistryTest {

    private static final List<VetoOption> E2_OPTIONS =
            List.of(VetoOption.ACCEPT_COMMAND, VetoOption.EXEC_DECLINE);

    private static ToolCall call() {
        return new ToolCall("run_command", Map.of("command", "echo hi"));
    }

    @Test
    void resolveOptionAcceptsAValidOptionName() throws Exception {
        HitlRegistry registry = new HitlRegistry();
        CompletableFuture<InterceptResolution> future =
                registry.register("agent-1", "call-1", call(), null, E2_OPTIONS);

        boolean resolved = registry.resolveOption("agent-1", "call-1", "ACCEPT_COMMAND");

        assertTrue(resolved, "resolveOption should return true for a pending veto");
        InterceptResolution resolution = future.get(1, TimeUnit.SECONDS);
        assertEquals(VetoOption.ACCEPT_COMMAND, resolution.option());
        assertEquals(VetoOption.ACCEPT_COMMAND.impliesMasking(), resolution.maskObservation());
    }

    @Test
    void resolveOptionIsCaseInsensitive() throws Exception {
        HitlRegistry registry = new HitlRegistry();
        CompletableFuture<InterceptResolution> future =
                registry.register("agent-1", "call-2", call(), null, E2_OPTIONS);

        assertTrue(registry.resolveOption("agent-1", "call-2", "exec_decline"));
        assertEquals(VetoOption.EXEC_DECLINE, future.get(1, TimeUnit.SECONDS).option());
    }

    @Test
    void resolveOptionFailsSafeOnAnInvalidName() throws Exception {
        HitlRegistry registry = new HitlRegistry();
        CompletableFuture<InterceptResolution> future =
                registry.register("agent-1", "call-3", call(), null, E2_OPTIONS);

        // An invalid choice resolves with the scenario's first refusal (EXEC_DECLINE) so the
        // agent unstucks fail-safe rather than executing a mis-approved call.
        assertTrue(registry.resolveOption("agent-1", "call-3", "not-a-real-option"));
        InterceptResolution resolution = future.get(1, TimeUnit.SECONDS);
        assertEquals(VetoOption.EXEC_DECLINE, resolution.option());
        assertTrue(resolution.option().isRefusal());
    }

    @Test
    void declineOptionResolvesWithTheFirstRefusal() throws Exception {
        HitlRegistry registry = new HitlRegistry();
        CompletableFuture<InterceptResolution> future =
                registry.register("agent-1", "call-4", call(), null, E2_OPTIONS);

        assertTrue(registry.declineOption("agent-1", "call-4"));
        InterceptResolution resolution = future.get(1, TimeUnit.SECONDS);
        assertEquals(VetoOption.EXEC_DECLINE, resolution.option());
        assertTrue(resolution.option().isRefusal());
    }

    @Test
    void resolveOptionReturnsFalseWhenNoVetoPending() {
        HitlRegistry registry = new HitlRegistry();
        assertFalse(
                registry.resolveOption("agent-1", "call-5", "ACCEPT_COMMAND"),
                "resolveOption should return false when no veto is pending");
    }

    @Test
    void declineOptionReturnsFalseWhenNoVetoPending() {
        HitlRegistry registry = new HitlRegistry();
        assertFalse(
                registry.declineOption("agent-1", "call-6"),
                "declineOption should return false when no veto is pending");
    }

    @Test
    void resolveReturnsFalseWhenNoVetoPending() {
        HitlRegistry registry = new HitlRegistry();
        assertFalse(
                registry.resolve(
                        "agent-1",
                        "call-7",
                        new InterceptResolution(VetoOption.EXEC_DECLINE, null)),
                "resolve should return false when no veto is pending");
    }
}
