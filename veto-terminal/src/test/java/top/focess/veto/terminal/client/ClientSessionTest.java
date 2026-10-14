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
import java.util.concurrent.atomic.AtomicLong;
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
    private final @NonNull AtomicLong sequences = new AtomicLong(1);

    private @NonNull ClientSession newSession(@NonNull ClientView view) {
        sequences.set(1);
        return new ClientSession(view, sequences::incrementAndGet);
    }

    @Test
    void acceptanceBroadcastsAndStaleRepliesCannotSettleAnotherOperation() {
        var view = new RecordingView();
        var session = newSession(view);
        var first =
                assertInstanceOf(
                        Objects.requireNonNull(Frame.Request.class), session.submit("first", null));
        session.submit("second", null);
        var prompt = new Frame.Prompt("Input", true, first.seq());
        session.onFrame(prompt);
        assertNull(
                session.onFrame(
                        new Frame.RequestAccepted(
                                first.seq(), new Scope.GlobalScope(), "episode")));
        assertSame(prompt, requirePrompt(session.promptView().activePrompt()));
        assertNull(
                session.onFrame(
                        new Frame.Done(new Scope.GlobalScope(), "episode", 5, null, "foreign", 0)));
        assertNull(session.onFrame(new Frame.Error("unrelated session failure", 0)));
        assertSame(prompt, requirePrompt(session.promptView().activePrompt()));
        assertNull(session.snapshot().username());
        var second =
                assertInstanceOf(
                        Objects.requireNonNull(Frame.Request.class),
                        session.onFrame(
                                new Frame.Done(
                                        new Scope.GlobalScope(),
                                        "episode",
                                        -1,
                                        null,
                                        null,
                                        first.seq())));
        assertTrue(second.seq() > first.seq());
        assertNull(
                session.onFrame(
                        new Frame.RequestAccepted(
                                first.seq(), new Scope.GlobalScope(), "episode")));
        assertNull(session.onFrame(new Frame.Error("late old failure", first.seq())));
        assertNull(session.onFrame(prompt));
        assertNull(session.promptView().activePrompt());
        assertEquals(ClientSession.State.RUNNING, session.state());
        assertEquals(0, session.cancel(first.seq()));
        assertEquals(second.seq(), session.cancel(second.seq()));
        assertNull(session.onFrame(new Frame.CancelResult(Frame.CancelStatus.ACCEPTED, 20)));
        assertEquals(ClientSession.State.RUNNING, session.state());
        assertNull(
                session.onFrame(
                        new Frame.Done(
                                new Scope.GlobalScope(), "episode", -1, null, null, second.seq())));
        assertEquals(ClientSession.State.IDLE, session.state());
    }

    @Test
    void correlatedErrorPreservesCommandPromptQueueAndLoginMetadata() {
        var view = new RecordingView();
        var session = newSession(view);
        session.submit("/login", null);
        session.onFrame(
                new Frame.Done(new Scope.GlobalScope(), null, -1, null, "alice", sequences.get()));
        session.submit("first", null);
        session.submit("second", null);
        var prompt = new Frame.Prompt("Confirm", false, sequences.get());
        session.onFrame(prompt);
        view.events.clear();
        assertNull(session.onFrame(new Frame.Error("late hint failure", 9)));
        assertEquals(ClientSession.State.PROMPTED, session.state());
        assertEquals(prompt, session.promptView().activePrompt());
        assertEquals("alice", session.promptView().username());
        assertEquals(List.of("error:Error: late hint failure"), view.events);
        assertEquals(
                new Frame.Request(sequences.get() + 1, null, null, "second"),
                session.onFrame(
                        new Frame.Done(
                                new Scope.GlobalScope(), null, -1, null, null, sequences.get())));
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
        public void onPromptCleared() {
            events.add("prompt-cleared");
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
        var client = newSession(view);
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
        client.onFrame(
                new Frame.Done(
                        new Scope.GlobalScope(), null, -1, "finished", null, sequences.get()));
        assertTrue(view.events.contains("dispatched:second"));
    }

    @Test
    void pluginEventsRenderWhileIdleAndDoNotAdvanceQueuedCommands() {
        var view = new RecordingView();
        var session = newSession(view);
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
        session.onFrame(
                new Frame.Done(
                        new Scope.GlobalScope(), null, -1, "finished", null, sequences.get()));
        assertTrue(view.events.contains("dispatched:second"));
    }

    @Test
    void commonEventsKeepToolAndNoticeRendering() {
        var view = new RecordingView();
        var session = newSession(view);
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
        ClientSession s = newSession(v);

        Frame f = requireFrame(s.submit("hello", null));

        Frame.Request request = assertInstanceOf(Objects.requireNonNull(Frame.Request.class), f);
        assertEquals("hello", request.raw());
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertTrue(v.events.contains("running"));
    }

    @Test
    void submitWhileRunningEnqueuesAndReturnsNull() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
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
        ClientSession s = newSession(v);
        s.submit("first", null); // RUNNING
        s.onFrame(new Frame.Prompt("password", true, 2));

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
            var session = newSession(view);
            session.submit("first", null);
            if (queued) session.submit("second", null);
            session.onFrame(new Frame.Prompt("password", true, 2));
            var displayed = session.promptView().activePrompt();
            session.onFrame(
                    new Frame.Done(new Scope.GlobalScope(), null, -1, null, null, sequences.get()));
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
        var session = newSession(view);
        session.submit("first", null);
        var original = new Frame.Prompt("password", true, 2);
        var replacement = new Frame.Prompt("password", true, 2);
        session.onFrame(original);
        session.onFrame(replacement);
        view.events.clear();

        assertNull(session.submit("synthetic-secret", original));
        assertTrue(replacement == session.promptView().activePrompt());
        assertEquals(ClientSession.State.PROMPTED, session.state());
        assertTrue(session.pendingQueue().isEmpty());
        assertTrue(view.events.isEmpty());
        assertEquals(
                new Frame.Input("current-secret", 2),
                session.submit("current-secret", replacement));
    }

    @Test
    void additionalApprovalWaitsWithoutReplacingTheDisplayedChoice() {
        var view = new RecordingView();
        var session = newSession(view);
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

        assertTrue(original == session.promptView().activePrompt());
        assertTrue(view.events.isEmpty());
        assertEquals(
                new Frame.VetoResult(scope, "first-call", "ACCEPT", 1),
                session.submit("ACCEPT", original));
        assertTrue(replacement == session.promptView().activePrompt());
        view.events.clear();
        assertNull(session.submit("ACCEPT", original));
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
                        new Frame.Prompt("password", true, 2),
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
            var session = newSession(view);
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
            assertEquals(
                    new Frame.Request(sequences.get() + 1, null, null, "second"),
                    session.onFrame(
                            new Frame.Done(
                                    new Scope.GlobalScope(),
                                    null,
                                    -1,
                                    null,
                                    null,
                                    sequences.get())));
            assertTrue(view.events.contains("dispatched:second"));
        }
    }

    @Test
    void idleApprovalCanBeAnsweredWithoutCreatingACommand() {
        var view = new RecordingView();
        var session = newSession(view);
        var approval = approval("call", 1);
        session.onFrame(approval);
        assertEquals(ClientSession.State.PROMPTED, session.state());
        assertTrue(approval == session.promptView().activePrompt());
        assertEquals(
                new Frame.VetoResult(approval.scope(), approval.callId(), "ACCEPT", approval.seq()),
                session.submit("ACCEPT", approval));
        assertEquals(ClientSession.State.IDLE, session.state());
        assertEquals(
                new Frame.Request(sequences.get() + 1, null, null, "next"),
                session.submit("next", null));
    }

    @Test
    void cancellingIdleApprovalsAdvancesOnlyTheApprovalQueue() {
        var session = newSession(new RecordingView());
        var first = approval("first", 1);
        var second = approval("second", 2);
        session.onFrame(first);
        session.onFrame(second);
        assertEquals(
                new Frame.VetoResult(first.scope(), "first", "EXEC_DECLINE", 1),
                session.declineApproval(first));
        assertTrue(second == session.promptView().activePrompt());
        assertNull(session.declineApproval(first));
        assertTrue(second == session.promptView().activePrompt());
        assertEquals(
                new Frame.VetoResult(second.scope(), "second", "EXEC_DECLINE", 2),
                session.declineApproval(second));
        assertEquals(ClientSession.State.IDLE, session.state());
        assertEquals(0, session.cancel(sequences.get()));
        assertTrue(session.pendingQueue().isEmpty());
    }

    @Test
    void missingRefusalLeavesTheApprovalVisibleWithoutCancellingAnotherRequest() {
        var view = new RecordingView();
        var session = newSession(view);
        var original = approval("call", 1);
        var approval =
                new Frame.Veto(
                        original.scope(),
                        original.callId(),
                        original.tool(),
                        original.scenario(),
                        List.of("ACCEPT"),
                        original.args(),
                        null,
                        null,
                        1);
        session.submit("first", null);
        session.onFrame(approval);
        assertNull(session.declineApproval(approval));
        assertEquals(0, session.cancel(sequences.get()));
        assertTrue(approval == session.promptView().activePrompt());
        assertTrue(view.events.stream().anyMatch(value -> value.startsWith("error:")));
    }

    @Test
    void commandPromptTakesPriorityAndKeepsSessionApprovals() {
        var session = newSession(new RecordingView());
        var first = approval("first", 1);
        var second = approval("second", 2);
        session.onFrame(first);
        assertEquals(
                new Frame.Request(sequences.get() + 1, null, null, "/login"),
                session.submit("/login", null));
        var password = new Frame.Prompt("password", true, 2);
        session.onFrame(password);
        session.onFrame(second);
        assertTrue(password == session.promptView().activePrompt());
        assertNull(session.submit("ACCEPT", first));
        assertEquals(new Frame.Input("secret", 2), session.submit("secret", password));
        assertTrue(first == session.promptView().activePrompt());
        assertNull(session.submit("stale-secret", password));
        session.submit("ACCEPT", first);
        assertTrue(second == session.promptView().activePrompt());
    }

    @Test
    void resolvingApprovalOnAnotherFrontendClearsOnlyTheMatchingInteraction() {
        var view = new RecordingView();
        var session = newSession(view);
        var first = approval("first", 1);
        var second = approval("second", 2);
        session.onFrame(first);
        session.onFrame(second);
        session.onFrame(
                new Delta(second.scope(), new Delta.ToolResult("first", "other agent", true, 1)));
        assertTrue(first == session.promptView().activePrompt());
        session.onFrame(
                new Delta(first.scope(), new Delta.ToolResult("first", "resolved", true, 1)));
        assertTrue(second == session.promptView().activePrompt());
        assertNull(session.submit("ACCEPT", first));
        session.onFrame(
                new Delta(second.scope(), new Delta.ToolResult("second", "resolved", true, 1)));
        assertEquals(ClientSession.State.IDLE, session.state());
        assertTrue(view.events.contains("prompt-cleared"));
    }

    @Test
    void commandCompletionPreservesAnIndependentApprovalAndRepliesDoNotAdvanceCommands() {
        var session = newSession(new RecordingView());
        var approval = approval("call", 1);
        session.submit("first", null);
        session.submit("second", null);
        session.onFrame(approval);
        assertEquals(
                new Frame.Request(sequences.get() + 1, null, null, "second"),
                session.onFrame(
                        new Frame.Done(
                                new Scope.GlobalScope(), null, -1, null, null, sequences.get())));
        assertTrue(approval == session.promptView().activePrompt());
        session.submit("ACCEPT", approval);
        assertEquals(ClientSession.State.RUNNING, session.state());
        assertNull(
                session.onFrame(
                        new Frame.Done(
                                new Scope.GlobalScope(), null, -1, null, null, sequences.get())));
        assertEquals(ClientSession.State.IDLE, session.state());
    }

    @Test
    void activationOutcomeRetainsApprovalsAlreadyReceivedForTheNewSession() {
        var session = newSession(new RecordingView());
        var oldApproval = approval("old", 1);
        var newScope =
                new Scope.AgentScope(oldApproval.scope().userId(), "new-session", "new-agent");
        var newApproval =
                new Frame.Veto(
                        newScope,
                        "new",
                        "tool",
                        "scenario",
                        List.of("ACCEPT", "EXEC_DECLINE"),
                        Map.of(),
                        null,
                        null,
                        2);
        session.submit("/login", null);
        session.onFrame(
                new Frame.Done(
                        oldApproval.scope().sessionScope(),
                        null,
                        -1,
                        null,
                        "alice",
                        sequences.get()));
        session.submit("/session activate new", null);
        session.onFrame(oldApproval);
        session.onFrame(newApproval);
        session.onFrame(
                new Frame.Done(newScope.sessionScope(), null, -1, null, "alice", sequences.get()));
        assertTrue(newApproval == session.promptView().activePrompt());
        assertNull(session.submit("ACCEPT", oldApproval));
        assertEquals(
                new Frame.VetoResult(newScope, "new", "ACCEPT", 2),
                session.submit("ACCEPT", newApproval));
        assertEquals(ClientSession.State.IDLE, session.state());
    }

    @Test
    void logoutDiscardsQueuedApprovalsAndStaleReplies() {
        var session = newSession(new RecordingView());
        var approval = approval("call", 1);
        session.submit("/login", null);
        session.onFrame(
                new Frame.Done(
                        approval.scope().sessionScope(), null, -1, null, "alice", sequences.get()));
        session.onFrame(approval);
        session.submit("/logout", null);
        session.onFrame(
                new Frame.Done(new Scope.GlobalScope(), null, -1, null, null, sequences.get()));
        assertEquals(ClientSession.State.IDLE, session.state());
        assertNull(session.submit("ACCEPT", approval));
    }

    private static Frame.@NonNull Veto approval(@NonNull String call, long sequence) {
        return new Frame.Veto(
                new Scope.AgentScope(UUID.randomUUID(), "session", "agent-" + call),
                call,
                "tool",
                "scenario",
                List.of("ACCEPT", "EXEC_DECLINE"),
                Map.of(),
                null,
                null,
                sequence);
    }

    // ═══ outbound: cancel ════════════════════════════════════════════

    @Test
    void approvalPickerRepliesWithExactApprovalIdentity() {
        // Approval choices use their own frame and preserve the exact pending call identity.
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
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
        ClientSession s = newSession(v);

        assertEquals(0, s.cancel(sequences.get()));
        assertEquals(ClientSession.State.IDLE, s.state());
    }

    @Test
    void cancelFromRunningPreservesQueue() {
        // : cancelling the in-flight command preserves the pending queue — dispatch-next-or-idle
        // may still dispatch a queued request after the cancelled command's terminal frame.
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null); // dispatched
        s.submit("second", null);
        s.submit("third", null); // queued

        assertTrue(s.cancel(sequences.get()) > 0);

        assertEquals(ClientSession.State.RUNNING, s.state()); // await the command's terminal frame
        assertEquals(List.of("second", "third"), s.pendingQueue()); // queue preserved
        assertNull(s.promptView().activePrompt());
    }

    @Test
    void cancelFromPromptedClearsPromptAndStaysRunning() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null);
        s.onFrame(new Frame.Prompt("pw", false, 2));

        assertTrue(s.cancel(sequences.get()) > 0);

        assertNull(s.promptView().activePrompt());
        assertEquals(ClientSession.State.RUNNING, s.state());
    }

    @Test
    void cancellationResultsNeverSettleCommandsOrClearReplacementPrompts() {
        var view = new RecordingView();
        var session = newSession(view);
        session.submit("first", null);
        session.submit("second", null);
        assertTrue(session.cancel(sequences.get()) > 0);
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
        assertEquals(
                new Frame.Request(sequences.get() + 1, null, null, "second"),
                session.onFrame(
                        new Frame.Done(
                                new Scope.GlobalScope(), null, -1, null, null, sequences.get())));
        var replacement = new Frame.Prompt("Replacement prompt", false, sequences.get());
        session.onFrame(replacement);
        for (var status : statuses) {
            assertNull(session.onFrame(new Frame.CancelResult(status, 2)));
            assertEquals(ClientSession.State.PROMPTED, session.state());
            assertSame(replacement, requireFrame(session.promptView().activePrompt()));
        }
        assertNull(
                session.onFrame(
                        new Frame.Done(
                                new Scope.GlobalScope(), null, -1, null, null, sequences.get())));
        assertNull(
                session.onFrame(new Frame.CancelResult(Frame.CancelStatus.NO_ACTIVE_REQUEST, 3)));
        assertEquals(ClientSession.State.IDLE, session.state());
    }

    // ═══ inbound: IDLE ═══════════════════════════════════════════════

    @Test
    void idleDisplaysUnscopedDeltaWithoutStartingACommand() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);

        Frame f = s.onFrame(Delta.message("chunk"));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertTrue(v.events.contains("delta:chunk"));
    }

    @Test
    void idleDisplaysGenericNoticeWithoutDispatch() {
        var view = new RecordingView();
        var session = newSession(view);
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
        ClientSession s = newSession(v);

        Frame f = s.onFrame(new Delta(new Scope.GlobalScope(), new Delta.Progress("loading", 50)));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertTrue(v.events.stream().anyMatch(e -> e.equals("progress:  ⏳ loading")));
    }

    @Test
    void idleRejectsOrphanPromptWithoutDisplay() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);

        Frame f = s.onFrame(new Frame.Prompt("pw", false, 2));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertFalse(v.events.contains("prompted"));
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("prompt:")));
        assertNull(s.promptView().activePrompt());
    }

    @Test
    void idleDoneCannotChangeIdentityOrStartACommand() {
        // An unsolicited completion carries no authority over local login metadata.
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);

        Frame f =
                s.onFrame(
                        new Frame.Done(
                                new Scope.GlobalScope(), null, -1, null, "alice", sequences.get()));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertNull(s.snapshot().username());
        // Done never displays content — no done event exists.
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("done:")));
    }

    @Test
    void idleErrorDisplaysAndStaysIdle() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);

        Frame f = s.onFrame(new Frame.Error("late", sequences.get()));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertTrue(v.events.contains("error:Error: late"));
    }

    // ═══ inbound: RUNNING ════════════════════════════════════════════

    @Test
    void runningDeltaDisplays() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null); // RUNNING

        s.onFrame(Delta.message("chunk"));

        assertTrue(v.events.contains("delta:chunk"));
    }

    @Test
    void runningThoughtDeltaRoutesToOnThought() {
        // A thought-kind Delta must route to onThought (distinct rendering), NOT to onDelta, so the
        // terminal can dim the agent's reasoning separately from user-facing message prose.
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
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
        ClientSession s = newSession(v);
        s.submit("first", null); // RUNNING

        s.onFrame(new Delta(new Scope.GlobalScope(), new Delta.Progress("loading", 50)));

        assertTrue(v.events.stream().anyMatch(e -> e.equals("progress:  ⏳ loading")));
    }

    @Test
    void runningPromptTransitionsToPrompted() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null); // RUNNING

        s.onFrame(new Frame.Prompt("password", true, 2));

        assertEquals(ClientSession.State.PROMPTED, s.state());
        Frame.Prompt prompt = requirePrompt(s.promptView().activePrompt());
        assertEquals("password", prompt.content());
        assertTrue(v.events.contains("prompted"));
        assertTrue(v.events.contains("prompt:password"));
    }

    @Test
    void runningDoneAppliesMetaAndDispatchesNext() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null); // dispatched
        s.submit("second", null); // enqueued

        v.events.clear();
        Frame f =
                requireFrame(
                        s.onFrame(
                                new Frame.Done(
                                        new Scope.GlobalScope(),
                                        null,
                                        -1,
                                        null,
                                        "alice",
                                        sequences.get())));

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
        ClientSession s = newSession(v);

        s.submit("hello", null);

        assertTrue(v.events.contains("dispatched:hello"));
    }

    @Test
    void enqueuedCommandDoesNotEchoUntilDispatched() {
        // A command enqueued while RUNNING must NOT echo "thinking…" when merely typed — it echoes
        // onCommandDispatched only when it is actually dispatched from the queue.
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null); // dispatched → echoes
        v.events.clear();

        s.submit("second", null); // enqueued while RUNNING — no echo yet
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("dispatched:")));
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertEquals(List.of("second"), s.pendingQueue());

        v.events.clear();
        s.onFrame(
                new Frame.Done(
                        new Scope.GlobalScope(),
                        null,
                        -1,
                        null,
                        null,
                        sequences.get())); // terminal → dispatch "second"
        assertTrue(v.events.contains("dispatched:second"));
    }

    @Test
    void runningDoneWithEmptyQueueGoesIdle() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null); // dispatched

        v.events.clear();
        Frame f =
                s.onFrame(
                        new Frame.Done(
                                new Scope.GlobalScope(), null, -1, null, null, sequences.get()));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertTrue(v.events.contains("idle"));
        // Done never displays content — no done event.
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("done:")));
    }

    @Test
    void runningErrorDisplaysAndDispatchesNext() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null); // dispatched
        s.submit("second", null); // queued

        v.events.clear();
        Frame f = requireFrame(s.onFrame(new Frame.Error("boom", sequences.get())));

        Frame.Request request = assertInstanceOf(Objects.requireNonNull(Frame.Request.class), f);
        assertEquals("second", request.raw());
        assertTrue(v.events.contains("error:Error: boom"));
    }

    // ═══ inbound: PROMPTED ═══════════════════════════════════════════

    @Test
    void promptedDeltaDisplays() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null);
        s.onFrame(new Frame.Prompt("pw", false, 2)); // PROMPTED

        s.onFrame(Delta.message("chunk"));

        assertTrue(v.events.contains("delta:chunk"));
        assertEquals(ClientSession.State.PROMPTED, s.state());
    }

    @Test
    void promptedPromptReplacesPrompt() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null);
        s.onFrame(new Frame.Prompt("pw1", false, 2)); // PROMPTED

        s.onFrame(new Frame.Prompt("pw2", false, 2)); // replace

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
        ClientSession s = newSession(v);
        s.submit("first", null);
        s.submit("second", null); // queued
        s.onFrame(new Frame.Prompt("pw", false, 2)); // PROMPTED
        requirePrompt(s.promptView().activePrompt());

        v.events.clear();
        Frame f =
                requireFrame(
                        s.onFrame(
                                new Frame.Done(
                                        new Scope.GlobalScope(),
                                        null,
                                        -1,
                                        null,
                                        "alice",
                                        sequences.get())));

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
        ClientSession s = newSession(v);
        s.submit("first", null);
        s.onFrame(new Frame.Prompt("pw", false, 2)); // PROMPTED
        requirePrompt(s.promptView().activePrompt());

        Frame f =
                s.onFrame(
                        new Frame.Done(
                                new Scope.GlobalScope(), null, -1, null, null, sequences.get()));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertNull(s.promptView().activePrompt());
    }

    @Test
    void promptedErrorClearsPromptDisplaysAndDispatchesNext() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null);
        s.submit("second", null); // queued
        s.onFrame(new Frame.Prompt("pw", false, 2)); // PROMPTED
        requirePrompt(s.promptView().activePrompt());

        Frame f = requireFrame(s.onFrame(new Frame.Error("boom", sequences.get())));

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
        ClientSession s = newSession(v);
        s.submit("first", null);
        s.onFrame(new Frame.Prompt("pw", false, 2)); // PROMPTED
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
        var session = newSession(view);
        var scope = new Scope.SessionScope(UUID.randomUUID(), "session");
        session.submit("/login", null);
        session.onFrame(new Frame.Done(scope, null, -1, null, "alice", sequences.get()));
        assertEquals("alice", session.snapshot().username());
        assertEquals("session", session.snapshot().sessionId());
        session.submit("first", null);
        session.onFrame(new Frame.Done(scope, "first", 5, null, "alice", sequences.get()));
        assertEquals(5, session.snapshot().turnCount());
        session.submit("second", null);
        session.onFrame(
                new Frame.Error(scope, "second", 6, "failed", sequences.get(), "alice", null));
        assertEquals(6, session.snapshot().turnCount());
        session.submit("/help", null);
        session.onFrame(new Frame.Done(scope, null, -1, null, "alice", sequences.get()));
        session.onFrame(
                new Frame.Error(scope, null, -1, "command failed", sequences.get(), "alice", null));
        assertEquals(6, session.snapshot().turnCount());
        session.submit("/logout", null);
        session.onFrame(
                new Frame.Done(new Scope.GlobalScope(), null, -1, null, null, sequences.get()));
        assertNull(session.snapshot().username());
        assertNull(session.snapshot().sessionId());
        assertEquals(0, session.snapshot().turnCount());
    }

    @Test
    void unchangedMetaDoesNotFireMetaChanged() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null);
        s.onFrame(
                new Frame.Done(new Scope.GlobalScope(), null, -1, null, "alice", sequences.get()));
        v.events.clear();

        s.submit("second", null);
        s.onFrame(
                new Frame.Done(
                        new Scope.GlobalScope(),
                        null,
                        -1,
                        null,
                        "alice",
                        sequences.get())); // same username

        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("meta:")));
    }

    // ═══ snapshots & concurrency ═══════════════════════════════════════════

    @Test
    void promptViewCapturesStateAndPromptAtomically() {
        RecordingView v = new RecordingView();
        ClientSession s = newSession(v);
        s.submit("first", null); // RUNNING

        s.onFrame(new Frame.Prompt("password", true, 2));
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
        ClientSession s = newSession(v);
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
        ClientSession s = newSession(v);
        s.submit("first", null); // dispatched → RUNNING
        s.submit("second", null); // enqueued
        s.submit("third", null); // enqueued → queue = [second, third]
        // The Done sets the username AND dispatches the next queued request ("second") — both in
        // one transition. statusView must reflect both effects from the same moment.
        s.onFrame(
                new Frame.Done(new Scope.GlobalScope(), null, -1, null, "alice", sequences.get()));

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
            ClientSession s = newSession(v);
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
                                s.onFrame(new Frame.Prompt("pw", false, 2));
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
