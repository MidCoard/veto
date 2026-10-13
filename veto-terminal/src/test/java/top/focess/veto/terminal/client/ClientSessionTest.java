package top.focess.veto.terminal.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.ProtocolClient;

/**
 * Direct, deterministic tests of {@link ClientSession}'s protocol logic — one per cell of the
 * interaction matrices. The session is transport-agnostic (fed frames via {@link
 * ClientSession#onFrame}, returns frames via {@link ClientSession#submit}/{@link
 * ClientSession#cancel}), so no ZMQ or {@link ProtocolClient} is needed — a recording {@link
 * ClientView} captures every render event.
 */
class ClientSessionTest {
    @Test
    void correlatedErrorPreservesCommandPromptQueueAndLoginMetadata() {
        var view = new RecordingView();
        var session = new ClientSession(view);
        session.onFrame(new Frame.Done(new Scope.GlobalScope(), null, -1, null, "alice"));
        session.submit("first", null);
        session.submit("second", null);
        var prompt = new Frame.Prompt("Confirm", false);
        session.onFrame(prompt);
        view.events.clear();
        assertNull(session.onFrame(new Frame.Error("late hint failure", 9)));
        assertEquals(ClientSession.State.PROMPTED, session.state());
        assertEquals(prompt, session.promptView().activePrompt());
        assertEquals("alice", session.promptView().username());
        assertEquals(List.of("error:Error: late hint failure"), view.events);
        assertEquals(new Frame.Request("second"), session.onFrame(new Frame.Done(null)));
    }

    /** A ClientView that records every callback as a string for assertion. */
    private static final class RecordingView implements ClientView {
        final @NonNull List<@NonNull String> events =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onDelta(@NonNull String content) {
            events.add("delta:" + content);
        }

        @Override
        public void onToolCall(Delta.@NonNull ToolCall frame) {
            events.add("tool-call:" + frame.tool());
        }

        @Override
        public void onToolResult(Delta.@NonNull ToolResult frame) {
            events.add("tool-result:" + frame.text());
        }

        @Override
        public void onThought(@NonNull String content) {
            events.add("thought:" + content);
        }

        @Override
        public void onProgress(@NonNull StyledText content) {
            events.add("progress:" + content.text());
        }

        @Override
        public void onPrompt(Frame.@NonNull ServerFrame prompt) {
            events.add("prompt:" + (prompt instanceof Frame.Prompt text ? text.content() : "veto"));
        }

        @Override
        public void onError(@NonNull StyledText content) {
            events.add("error:" + content.text());
        }

        @Override
        public void onTerminate(@NonNull StyledText content) {
            events.add("terminate:" + content.text());
        }

        @Override
        public void onIdle() {
            events.add("idle");
        }

        @Override
        public void onRunning() {
            events.add("running");
        }

        @Override
        public void onCommandDispatched(@NonNull String line) {
            events.add("dispatched:" + line);
        }

        @Override
        public void onPrompted() {
            events.add("prompted");
        }

        @Override
        public void onMetaChanged(ClientSession.@NonNull SessionMeta meta) {
            events.add("meta:" + meta.username() + "/" + meta.turnCount() + "/" + meta.sessionId());
        }
    }

    // ═══ outbound: submit ════════════════════════════════════════════

    @Test
    void tokenUsageRendersWithoutFinishingTheCommand() {
        var view = new RecordingView();
        var client = new ClientSession(view);
        client.submit("first", null);
        client.submit("second", null);
        client.onFrame(
                new Delta(
                        new Scope.AgentScope(
                                UUID.randomUUID(), UUID.randomUUID().toString(), "agent"),
                        new Delta.TokenUsage(
                                "call", 100, 20, null, null, 8192, null, null, null, 1)));
        assertTrue(view.events.contains("progress:Tokens: 100 input, 20 output"));
        assertFalse(view.events.contains("dispatched:second"));
        client.onFrame(new Frame.Done("finished"));
        assertTrue(view.events.contains("dispatched:second"));
    }

    @Test
    void pluginEventsRenderWhileIdleAndDoNotAdvanceQueuedCommands() {
        var view = new RecordingView();
        var session = new ClientSession(view);
        var event =
                new Frame.PluginAction(
                        new Scope.SessionScope(UUID.randomUUID(), UUID.randomUUID().toString()),
                        "task.updated",
                        JsonNodeFactory.instance.objectNode().put("status", "ready"));
        session.onFrame(event);
        assertTrue(view.events.stream().anyMatch(value -> value.startsWith("delta:task.updated")));
        session.submit("first", null);
        session.submit("second", null);
        view.events.clear();
        assertNull(session.onFrame(event));
        assertFalse(view.events.contains("dispatched:second"));
        session.onFrame(new Frame.Done("finished"));
        assertTrue(view.events.contains("dispatched:second"));
    }

    @Test
    void commonEventsKeepToolAndNoticeRendering() {
        var view = new RecordingView();
        var session = new ClientSession(view);
        assertInstanceOf(
                Objects.requireNonNull(Frame.Request.class),
                requireFrame(session.submit("prompt", null)));
        assertNull(
                session.onFrame(
                        new Delta(
                                new Scope.AgentScope(
                                        UUID.randomUUID(), UUID.randomUUID().toString(), "agent"),
                                new Delta.ToolCall(
                                        null,
                                        "read_file",
                                        JsonNodeFactory.instance.objectNode(),
                                        0))));
        assertNull(
                session.onFrame(
                        new Delta(
                                new Scope.AgentScope(
                                        UUID.randomUUID(), UUID.randomUUID().toString(), "agent"),
                                new Delta.ToolResult(null, "contents", true, 0))));
        assertNull(
                session.onFrame(
                        new Delta(
                                new Scope.AgentScope(
                                        UUID.randomUUID(), UUID.randomUUID().toString(), "agent"),
                                new Delta.Notice("Server guidance", Delta.Level.INFO))));
        assertTrue(view.events.contains("tool-call:read_file"));
        assertTrue(view.events.contains("tool-result:contents"));
        assertTrue(view.events.contains("progress:Server guidance"));
        assertEquals(ClientSession.State.RUNNING, session.state());
    }

    @Test
    void submitFromIdleDispatchesRequestAndTransitionsToRunning() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        Frame f = requireFrame(s.submit("hello", null));

        Frame.Request request = assertInstanceOf(Objects.requireNonNull(Frame.Request.class), f);
        assertEquals("hello", request.raw());
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertTrue(v.events.contains("running"));
    }

    @Test
    void submitWhileRunningEnqueuesAndReturnsNull() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // IDLE → RUNNING, dispatched

        v.events.clear();
        Frame f = s.submit("second", null); // RUNNING → enqueue

        assertNull(f);
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertEquals(List.of("second"), s.pendingQueue());
    }

    @Test
    void submitFromPromptedReturnsInputAndTransitionsToRunning() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // RUNNING
        s.onFrame(new Frame.Prompt("password", true));

        assertEquals(ClientSession.State.PROMPTED, s.state());
        requirePrompt(s.promptView().activePrompt());
        v.events.clear();

        Frame f = requireFrame(s.submit("secret", s.promptView().activePrompt()));

        Frame.Input input = assertInstanceOf(Objects.requireNonNull(Frame.Input.class), f);
        assertEquals("secret", input.raw());
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertNull(s.promptView().activePrompt());
    }

    @Test
    void submitAfterPromptResolvedDiscardsStaleReply() {
        for (boolean queued : new boolean[] {false, true}) {
            var view = new RecordingView();
            var session = new ClientSession(view);
            session.submit("first", null);
            if (queued) session.submit("second", null);
            session.onFrame(new Frame.Prompt("password", true));
            var displayed = session.promptView().activePrompt();
            session.onFrame(new Frame.Done(null));
            view.events.clear();

            assertNull(session.submit("synthetic-secret", displayed));
            assertTrue(view.events.isEmpty());
            assertTrue(session.pendingQueue().isEmpty());
            assertEquals(
                    queued ? ClientSession.State.RUNNING : ClientSession.State.IDLE,
                    session.state());
        }
    }

    @Test
    void equalReplacementPromptDoesNotAcceptThePreviousReply() {
        var view = new RecordingView();
        var session = new ClientSession(view);
        session.submit("first", null);
        var original = new Frame.Prompt("password", true);
        var replacement = new Frame.Prompt("password", true);
        session.onFrame(original);
        session.onFrame(replacement);
        view.events.clear();

        assertNull(session.submit("synthetic-secret", original));
        assertTrue(replacement == session.promptView().activePrompt());
        assertEquals(ClientSession.State.PROMPTED, session.state());
        assertTrue(session.pendingQueue().isEmpty());
        assertTrue(view.events.isEmpty());
        assertEquals(
                new Frame.Input("current-secret"), session.submit("current-secret", replacement));
    }

    @Test
    void replacementApprovalDoesNotReceiveAnEarlierApprovalChoice() {
        var view = new RecordingView();
        var session = new ClientSession(view);
        session.submit("first", null);
        var scope = new Scope.AgentScope(UUID.randomUUID(), "session", "agent");
        var original =
                new Frame.Veto(
                        scope,
                        "first-call",
                        "tool",
                        "scenario",
                        List.of("ACCEPT"),
                        Map.of(),
                        null,
                        null,
                        1);
        var replacement =
                new Frame.Veto(
                        scope,
                        "second-call",
                        "tool",
                        "scenario",
                        List.of("ACCEPT"),
                        Map.of(),
                        null,
                        null,
                        2);
        session.onFrame(original);
        session.onFrame(replacement);
        view.events.clear();

        assertNull(session.submit("ACCEPT", original));
        assertTrue(replacement == session.promptView().activePrompt());
        assertEquals(ClientSession.State.PROMPTED, session.state());
        assertTrue(session.pendingQueue().isEmpty());
        assertTrue(view.events.isEmpty());
        assertEquals(
                new Frame.VetoResult(scope, "second-call", "ACCEPT", 2),
                session.submit("ACCEPT", replacement));
    }

    @Test
    void commandReadBeforeInteractionArrivedRemainsQueuedCommandInput() {
        var scope = new Scope.AgentScope(UUID.randomUUID(), "session", "agent");
        List<Frame.@NonNull ServerFrame> interactions =
                List.of(
                        new Frame.Prompt("password", true),
                        new Frame.Veto(
                                scope,
                                "call",
                                "tool",
                                "scenario",
                                List.of("ACCEPT"),
                                Map.of(),
                                null,
                                null,
                                1));
        for (var interaction : interactions) {
            var view = new RecordingView();
            var session = new ClientSession(view);
            session.submit("first", null);
            var displayed = session.promptView().activePrompt();
            assertNull(displayed);
            session.onFrame(interaction);
            view.events.clear();

            assertNull(session.submit("second", displayed));
            assertTrue(interaction == session.promptView().activePrompt());
            assertEquals(ClientSession.State.PROMPTED, session.state());
            assertEquals(List.of("second"), session.pendingQueue());
            assertTrue(view.events.isEmpty());
            assertEquals(new Frame.Request("second"), session.onFrame(new Frame.Done(null)));
            assertTrue(view.events.contains("dispatched:second"));
        }
    }

    // ═══ outbound: cancel ════════════════════════════════════════════

    @Test
    void approvalPickerRepliesWithExactApprovalIdentity() {
        // Approval choices use their own frame and preserve the exact pending call identity.
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // RUNNING

        Frame.Veto payload =
                new Frame.Veto(
                        new Scope.AgentScope(
                                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                                (UUID.randomUUID()).toString(),
                                "agent-1"),
                        "call-1",
                        "run_command",
                        "EXEC_NETWORK",
                        List.of("ACCEPT_COMMAND", "EXEC_DECLINE"),
                        Map.of("command", "echo hi"),
                        null,
                        null,
                        42);
        s.onFrame(payload);

        assertEquals(ClientSession.State.PROMPTED, s.state());
        Frame.Veto activePayload = requireVeto(s.promptView().activePrompt());
        assertEquals("call-1", activePayload.callId());
        assertEquals("run_command", activePayload.tool());
        assertEquals(List.of("ACCEPT_COMMAND", "EXEC_DECLINE"), activePayload.options());
        assertTrue(v.events.contains("prompted"));
        assertTrue(v.events.contains("prompt:veto"));

        Frame f = requireFrame(s.submit("ACCEPT_COMMAND", activePayload));
        Frame.VetoResult input =
                assertInstanceOf(Objects.requireNonNull(Frame.VetoResult.class), f);
        assertEquals("ACCEPT_COMMAND", input.option());
        assertEquals(42, input.seq());
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertNull(s.promptView().activePrompt());
    }

    @Test
    void cancelFromIdleReturnsFalseAsShutdownSignal() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        assertFalse(s.cancel());
        assertEquals(ClientSession.State.IDLE, s.state());
    }

    @Test
    void cancelFromRunningPreservesQueue() {
        // : cancelling the in-flight command preserves the pending queue — dispatch-next-or-idle
        // may still dispatch a queued request after the cancelled command's terminal frame.
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // dispatched
        s.submit("second", null);
        s.submit("third", null); // queued

        assertTrue(s.cancel());

        assertEquals(ClientSession.State.RUNNING, s.state()); // await the command's terminal frame
        assertEquals(List.of("second", "third"), s.pendingQueue()); // queue preserved
        assertNull(s.promptView().activePrompt());
    }

    @Test
    void cancelFromPromptedClearsPromptAndStaysRunning() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null);
        s.onFrame(new Frame.Prompt("pw", false));

        assertTrue(s.cancel());

        assertNull(s.promptView().activePrompt());
        assertEquals(ClientSession.State.RUNNING, s.state());
    }

    @Test
    void cancellationResultsNeverSettleCommandsOrClearReplacementPrompts() {
        var view = new RecordingView();
        var session = new ClientSession(view);
        session.submit("first", null);
        session.submit("second", null);
        assertTrue(session.cancel());
        var statuses =
                List.of(
                        Frame.CancelStatus.ACCEPTED,
                        Frame.CancelStatus.REJECTED,
                        Frame.CancelStatus.NO_ACTIVE_REQUEST);
        for (var status : statuses) {
            assertNull(session.onFrame(new Frame.CancelResult(status, 2)));
            assertEquals(ClientSession.State.RUNNING, session.state());
            assertEquals(List.of("second"), session.pendingQueue());
        }
        assertEquals(new Frame.Request("second"), session.onFrame(new Frame.Done(null)));
        var replacement = new Frame.Prompt("Replacement prompt", false);
        session.onFrame(replacement);
        for (var status : statuses) {
            assertNull(session.onFrame(new Frame.CancelResult(status, 2)));
            assertEquals(ClientSession.State.PROMPTED, session.state());
            assertSame(replacement, requireFrame(session.promptView().activePrompt()));
        }
        assertNull(session.onFrame(new Frame.Done(null)));
        assertNull(
                session.onFrame(new Frame.CancelResult(Frame.CancelStatus.NO_ACTIVE_REQUEST, 3)));
        assertEquals(ClientSession.State.IDLE, session.state());
    }

    // ═══ inbound: IDLE ═══════════════════════════════════════════════

    @Test
    void idleDisplaysUnscopedDeltaWithoutStartingACommand() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        Frame f = s.onFrame(Delta.message("chunk"));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertTrue(v.events.contains("delta:chunk"));
    }

    @Test
    void idleDisplaysGenericNoticeWithoutDispatch() {
        var view = new RecordingView();
        var session = new ClientSession(view);
        assertNull(
                session.onFrame(
                        new Delta(
                                new Scope.AgentScope(
                                        UUID.randomUUID(), UUID.randomUUID().toString(), "agent"),
                                new Delta.Notice("Context compacted", Delta.Level.INFO))));
        assertEquals(ClientSession.State.IDLE, session.state());
        assertEquals(List.of("progress:Context compacted"), view.events);
    }

    @Test
    void idleDisplaysProgressWithoutDispatch() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        Frame f = s.onFrame(new Delta(new Scope.GlobalScope(), new Delta.Progress("loading", 50)));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertTrue(v.events.stream().anyMatch(e -> e.equals("progress:  ⏳ loading")));
    }

    @Test
    void idleRejectsOrphanPromptWithoutDisplay() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        Frame f = s.onFrame(new Frame.Prompt("pw", false));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertFalse(v.events.contains("prompted"));
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("prompt:")));
        assertNull(s.promptView().activePrompt());
    }

    @Test
    void idleDoneAppliesMetaAndStaysIdle() {
        // IDLE × Done: a late/duplicate completion applies meta and stays IDLE — no display.
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        Frame f = s.onFrame(new Frame.Done(new Scope.GlobalScope(), null, -1, null, "alice"));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertEquals("alice", s.snapshot().username());
        // Done never displays content — no done event exists.
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("done:")));
    }

    @Test
    void idleErrorDisplaysAndStaysIdle() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        Frame f = s.onFrame(Frame.Error.ofError("late"));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertTrue(v.events.contains("error:Error: late"));
    }

    // ═══ inbound: RUNNING ════════════════════════════════════════════

    @Test
    void runningDeltaDisplays() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // RUNNING

        s.onFrame(Delta.message("chunk"));

        assertTrue(v.events.contains("delta:chunk"));
    }

    @Test
    void runningThoughtDeltaRoutesToOnThought() {
        // A thought-kind Delta must route to onThought (distinct rendering), NOT to onDelta, so the
        // terminal can dim the agent's reasoning separately from user-facing message prose.
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // RUNNING

        s.onFrame(
                new Delta(
                        new Scope.AgentScope(
                                UUID.randomUUID(), UUID.randomUUID().toString(), "agent"),
                        new Delta.Thought("reasoning chunk", 0)));

        assertTrue(v.events.contains("thought:reasoning chunk"));
        assertFalse(
                v.events.contains("delta:reasoning chunk"),
                "a thought-kind Delta must NOT also fire onDelta");
    }

    @Test
    void runningProgressDisplays() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // RUNNING

        s.onFrame(new Delta(new Scope.GlobalScope(), new Delta.Progress("loading", 50)));

        assertTrue(v.events.stream().anyMatch(e -> e.equals("progress:  ⏳ loading")));
    }

    @Test
    void runningPromptTransitionsToPrompted() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // RUNNING

        s.onFrame(new Frame.Prompt("password", true));

        assertEquals(ClientSession.State.PROMPTED, s.state());
        Frame.Prompt prompt = requirePrompt(s.promptView().activePrompt());
        assertEquals("password", prompt.content());
        assertTrue(v.events.contains("prompted"));
        assertTrue(v.events.contains("prompt:password"));
    }

    @Test
    void runningDoneAppliesMetaAndDispatchesNext() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // dispatched
        s.submit("second", null); // enqueued

        v.events.clear();
        Frame f =
                requireFrame(
                        s.onFrame(
                                new Frame.Done(new Scope.GlobalScope(), null, -1, null, "alice")));

        Frame.Request request = assertInstanceOf(Objects.requireNonNull(Frame.Request.class), f);
        assertEquals("second", request.raw());
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertTrue(v.events.contains("meta:alice/0/null"));
        assertTrue(v.events.contains("running"));
        // The auto-dispatched queued command echoes onCommandDispatched (
        // dispatch-next-or-idle).
        assertTrue(v.events.contains("dispatched:second"));
        // Done never displays content — no done event.
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("done:")));
        assertTrue(s.pendingQueue().isEmpty());
    }

    @Test
    void submitFromIdleEchoesDispatchedCommand() {
        // A command typed at IDLE dispatches now and fires onCommandDispatched on the calling
        // thread — the echo is owned by the dispatch event, not the submit call site.
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        s.submit("hello", null);

        assertTrue(v.events.contains("dispatched:hello"));
    }

    @Test
    void enqueuedCommandDoesNotEchoUntilDispatched() {
        // A command enqueued while RUNNING must NOT echo "thinking…" when merely typed — it echoes
        // onCommandDispatched only when it is actually dispatched from the queue.
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // dispatched → echoes
        v.events.clear();

        s.submit("second", null); // enqueued while RUNNING — no echo yet
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("dispatched:")));
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertEquals(List.of("second"), s.pendingQueue());

        v.events.clear();
        s.onFrame(new Frame.Done(null)); // terminal → dispatch "second"
        assertTrue(v.events.contains("dispatched:second"));
    }

    @Test
    void runningDoneWithEmptyQueueGoesIdle() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // dispatched

        v.events.clear();
        Frame f = s.onFrame(new Frame.Done(null));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertTrue(v.events.contains("idle"));
        // Done never displays content — no done event.
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("done:")));
    }

    @Test
    void runningErrorDisplaysAndDispatchesNext() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // dispatched
        s.submit("second", null); // queued

        v.events.clear();
        Frame f = requireFrame(s.onFrame(Frame.Error.ofError("boom")));

        Frame.Request request = assertInstanceOf(Objects.requireNonNull(Frame.Request.class), f);
        assertEquals("second", request.raw());
        assertTrue(v.events.contains("error:Error: boom"));
    }

    // ═══ inbound: PROMPTED ═══════════════════════════════════════════

    @Test
    void promptedDeltaDisplays() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null);
        s.onFrame(new Frame.Prompt("pw", false)); // PROMPTED

        s.onFrame(Delta.message("chunk"));

        assertTrue(v.events.contains("delta:chunk"));
        assertEquals(ClientSession.State.PROMPTED, s.state());
    }

    @Test
    void promptedPromptReplacesPrompt() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null);
        s.onFrame(new Frame.Prompt("pw1", false)); // PROMPTED

        s.onFrame(new Frame.Prompt("pw2", false)); // replace

        assertEquals(ClientSession.State.PROMPTED, s.state());
        Frame.Prompt prompt = requirePrompt(s.promptView().activePrompt());
        assertEquals("pw2", prompt.content());
        // A replace does not re-fire the onPrompted transition signal (already prompted).
        long promptedCount = v.events.stream().filter("prompted"::equals).count();
        assertEquals(1, promptedCount);
    }

    @Test
    void promptedDoneAppliesMetaClearsPromptAndDispatchesNext() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null);
        s.submit("second", null); // queued
        s.onFrame(new Frame.Prompt("pw", false)); // PROMPTED
        requirePrompt(s.promptView().activePrompt());

        v.events.clear();
        Frame f =
                requireFrame(
                        s.onFrame(
                                new Frame.Done(new Scope.GlobalScope(), null, -1, null, "alice")));

        Frame.Request request = assertInstanceOf(Objects.requireNonNull(Frame.Request.class), f);
        assertEquals("second", request.raw());
        assertEquals(ClientSession.State.RUNNING, s.state());
        // The prompt the terminal frame resolved must be cleared ( PROMPTED × Done).
        assertNull(s.promptView().activePrompt());
        assertEquals("alice", s.snapshot().username());
    }

    @Test
    void promptedDoneWithEmptyQueueClearsPromptAndGoesIdle() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null);
        s.onFrame(new Frame.Prompt("pw", false)); // PROMPTED
        requirePrompt(s.promptView().activePrompt());

        Frame f = s.onFrame(new Frame.Done(null));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertNull(s.promptView().activePrompt());
    }

    @Test
    void promptedErrorClearsPromptDisplaysAndDispatchesNext() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null);
        s.submit("second", null); // queued
        s.onFrame(new Frame.Prompt("pw", false)); // PROMPTED
        requirePrompt(s.promptView().activePrompt());

        Frame f = requireFrame(s.onFrame(Frame.Error.ofError("boom")));

        Frame.Request request = assertInstanceOf(Objects.requireNonNull(Frame.Request.class), f);
        assertEquals("second", request.raw());
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertNull(s.promptView().activePrompt()); // cleared (PROMPTED × Error)
        assertTrue(v.events.contains("error:Error: boom"));
    }

    // ═══ inbound: Terminate (any state) ═══════════════════════════════

    @Test
    void terminateFiresTerminateAndClearsPrompt() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null);
        s.onFrame(new Frame.Prompt("pw", false)); // PROMPTED
        requirePrompt(s.promptView().activePrompt());

        Frame f = s.onFrame(new Frame.Terminate("bye now"));

        assertNull(f); // no dispatch on terminate
        assertTrue(v.events.contains("terminate:bye now"));
        assertNull(s.promptView().activePrompt()); // cleared on teardown
    }

    // ═══ meta application ═════════════════════════════════════════════════

    @Test
    void outcomesUpdateIdentityAndTurnCountAndLogoutClearsThem() {
        var view = new RecordingView();
        var session = new ClientSession(view);
        var scope = new Scope.SessionScope(UUID.randomUUID(), "session");
        session.submit("/login", null);
        session.onFrame(new Frame.Done(scope, null, -1, null, "alice"));
        assertEquals("alice", session.snapshot().username());
        assertEquals("session", session.snapshot().sessionId());
        session.submit("first", null);
        session.onFrame(new Frame.Done(scope, "first", 5, null, "alice"));
        assertEquals(5, session.snapshot().turnCount());
        session.submit("second", null);
        session.onFrame(new Frame.Error(scope, "second", 6, "failed", 0, "alice"));
        assertEquals(6, session.snapshot().turnCount());
        session.submit("/help", null);
        session.onFrame(new Frame.Done(scope, null, -1, null, "alice"));
        session.onFrame(new Frame.Error(scope, null, -1, "command failed", 0, "alice"));
        assertEquals(6, session.snapshot().turnCount());
        session.submit("/logout", null);
        session.onFrame(new Frame.Done(null));
        assertNull(session.snapshot().username());
        assertNull(session.snapshot().sessionId());
        assertEquals(0, session.snapshot().turnCount());
    }

    @Test
    void unchangedMetaDoesNotFireMetaChanged() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null);
        s.onFrame(new Frame.Done(new Scope.GlobalScope(), null, -1, null, "alice"));
        v.events.clear();

        s.onFrame(
                new Frame.Done(new Scope.GlobalScope(), null, -1, null, "alice")); // same username

        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("meta:")));
    }

    // ═══ snapshots & concurrency ═══════════════════════════════════════════

    @Test
    void promptViewCapturesStateAndPromptAtomically() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // RUNNING

        s.onFrame(new Frame.Prompt("password", true));
        ClientSession.PromptView prompted = s.promptView();
        assertEquals(ClientSession.State.PROMPTED, prompted.state());
        Frame.Prompt activePrompt = requirePrompt(prompted.activePrompt());
        assertEquals("password", activePrompt.content());
        assertEquals(ClientSession.State.PROMPTED, prompted.state()); // unchanged by the read

        s.submit("secret", s.promptView().activePrompt()); // PROMPTED → RUNNING (Input sent)
        ClientSession.PromptView running = s.promptView();
        assertEquals(ClientSession.State.RUNNING, running.state());
        assertNull(running.activePrompt()); // cleared on submit, same moment as the state
    }

    @Test
    void pendingQueueSnapshotIsImmutableCopy() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("a", null); // dispatched
        s.submit("b", null);
        s.submit("c", null);

        List<String> q = s.pendingQueue();

        assertEquals(List.of("b", "c"), q);
        assertThrows(Objects.requireNonNull(UnsupportedOperationException.class), () -> q.add("d"));
    }

    @Test
    void statusViewCapturesUsernameAndQueueAtomically() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first", null); // dispatched → RUNNING
        s.submit("second", null); // enqueued
        s.submit("third", null); // enqueued → queue = [second, third]
        // The Done sets the username AND dispatches the next queued request ("second") — both in
        // one transition. statusView must reflect both effects from the same moment.
        s.onFrame(new Frame.Done(new Scope.GlobalScope(), null, -1, null, "alice"));

        ClientSession.StatusView view = s.statusView();
        assertEquals("alice", view.username()); // username just set by the Done
        assertEquals(List.of("third"), view.pending()); // "second" dispatched, "third" remains
        assertThrows(
                Objects.requireNonNull(UnsupportedOperationException.class),
                () -> view.pending().add("x"));
    }

    @Test
    void promptDuringSubmitIsSerializedByTheMonitor() throws InterruptedException {
        // A Prompt arriving concurrently with submit must not corrupt state or deadlock — the
        // self-owned monitor serializes them. Outcome is order-dependent but always valid.
        for (int i = 0; i < 200; i++) {
            RecordingView v = new RecordingView();
            ClientSession s = new ClientSession(v);
            s.submit("first", null); // RUNNING

            CountDownLatch bothStarted = new CountDownLatch(1);
            Thread a = new Thread(() -> s.submit("reply", null));
            Thread b =
                    new Thread(
                            () -> {
                                try {
                                    bothStarted.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                s.onFrame(new Frame.Prompt("pw", false));
                            });

            a.start();
            bothStarted.countDown();
            b.start();
            a.join();
            b.join();

            // The command was read before either thread ran, so it cannot answer the new Prompt.
            assertEquals(ClientSession.State.PROMPTED, s.state());
            assertEquals(List.of("reply"), s.pendingQueue());
        }
    }

    private static @NonNull Frame requireFrame(Frame frame) {
        if (frame == null) {
            throw new AssertionError("Expected a client frame");
        }
        return frame;
    }

    private static Frame.@NonNull Prompt requirePrompt(Frame.ServerFrame prompt) {
        if (!(prompt instanceof Frame.Prompt text))
            throw new AssertionError("Expected an active text prompt");
        return text;
    }

    private static Frame.@NonNull Veto requireVeto(Frame.ServerFrame payload) {
        if (!(payload instanceof Frame.Veto veto)) throw new AssertionError("Expected an approval");
        return veto;
    }
}
