package top.focess.veto.client.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;

/**
 * Direct, deterministic tests of {@link ClientSession}'s protocol logic — one per cell of the
 * interaction matrices. The session is transport-agnostic (fed frames via {@link
 * ClientSession#onFrame}, returns frames via {@link ClientSession#submit}/{@link
 * ClientSession#cancel}), so no ZMQ or {@link top.focess.veto.contract.IpcClient} is needed — a
 * recording {@link ClientView} captures every render event.
 */
class ClientSessionTest {

    /** A ClientView that records every callback as a string for assertion. */
    private static final class RecordingView implements ClientView {
        final @NonNull List<@NonNull String> events =
                Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void onDelta(@NonNull String content) {
            events.add("delta:" + content);
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
        public void onPrompt(IpcFrame.@NonNull Prompt prompt) {
            events.add("prompt:" + prompt.content());
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
    void submitFromIdleDispatchesRequestAndTransitionsToRunning() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        IpcFrame.ClientFrame f = requireFrame(s.submit("hello"));

        IpcFrame.Request request = assertInstanceOf(nonNullClass(IpcFrame.Request.class), f);
        assertEquals("hello", request.raw());
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertTrue(v.events.contains("running"));
    }

    @Test
    void submitWhileRunningEnqueuesAndReturnsNull() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // IDLE → RUNNING, dispatched

        v.events.clear();
        IpcFrame.ClientFrame f = s.submit("second"); // RUNNING → enqueue

        assertNull(f);
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertEquals(List.of("second"), s.pendingQueue());
    }

    @Test
    void submitFromPromptedReturnsInputAndTransitionsToRunning() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // RUNNING
        s.onFrame(new IpcFrame.Prompt("password", true));

        assertEquals(ClientSession.State.PROMPTED, s.state());
        requirePrompt(s.promptView().activePrompt());
        v.events.clear();

        IpcFrame.ClientFrame f = requireFrame(s.submit("secret"));

        IpcFrame.Input input = assertInstanceOf(nonNullClass(IpcFrame.Input.class), f);
        assertEquals("secret", input.raw());
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertNull(s.promptView().activePrompt());
    }

    @Test
    void submitAfterPromptResolvedDiscardsStaleReply() {
        // stale-reply rule: a line typed for a Prompt that has already resolved (a terminal
        // frame raced the reply) is treated as a NEW command (or enqueued) — never sent as a stale
        // Input. submit routes on the live state, so a reply typed after the prompt vanished
        // becomes a Request, not an Input.
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // RUNNING
        s.onFrame(new IpcFrame.Prompt("pw", false)); // PROMPTED
        s.onFrame(
                new IpcFrame.Done(
                        Map.of(), null)); // terminal raced the reply → RUNNING (idle dispatch)
        assertEquals(ClientSession.State.IDLE, s.state());
        assertNull(s.promptView().activePrompt());

        // The user's "reply" arrives after the prompt is gone — it is a new command, not an Input.
        IpcFrame.ClientFrame f = requireFrame(s.submit("not-a-password"));

        IpcFrame.Request request = assertInstanceOf(nonNullClass(IpcFrame.Request.class), f);
        assertEquals("not-a-password", request.raw());
        assertEquals(ClientSession.State.RUNNING, s.state());
    }

    // ═══ outbound: cancel ════════════════════════════════════════════

    @Test
    void runningVetoPromptExposesVetoPayloadAndSubmitsOptionName() {
        // A Prompt carrying a VetoPayload must expose the payload in the prompt view (so the
        // terminal renders a picker), and the user's picker reply is submitted as an Input
        // carrying the chosen option name (the server's veto-first routing resolves it).
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // RUNNING

        IpcFrame.VetoPayload payload =
                new IpcFrame.VetoPayload(
                        "agent-1",
                        "call-1",
                        "run_command",
                        "EXEC_NETWORK",
                        List.of("ACCEPT_COMMAND", "EXEC_DECLINE"),
                        Map.of("command", "echo hi"));
        s.onFrame(new IpcFrame.Prompt("HITL: run_command", false, payload));

        assertEquals(ClientSession.State.PROMPTED, s.state());
        IpcFrame.Prompt active = requirePrompt(s.promptView().activePrompt());
        IpcFrame.VetoPayload activePayload = requireVetoPayload(active.veto());
        assertEquals("call-1", activePayload.callId());
        assertEquals("run_command", activePayload.tool());
        assertEquals(List.of("ACCEPT_COMMAND", "EXEC_DECLINE"), activePayload.options());
        assertTrue(v.events.contains("prompted"));
        assertTrue(v.events.contains("prompt:HITL: run_command"));

        IpcFrame.ClientFrame f = requireFrame(s.submit("ACCEPT_COMMAND"));
        IpcFrame.Input input = assertInstanceOf(nonNullClass(IpcFrame.Input.class), f);
        assertEquals("ACCEPT_COMMAND", input.raw());
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertNull(s.promptView().activePrompt());
    }

    @Test
    void cancelFromIdleReturnsNullAsShutdownSignal() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        assertNull(s.cancel());
        assertEquals(ClientSession.State.IDLE, s.state());
    }

    @Test
    void cancelFromRunningReturnsCancelAndPreservesQueue() {
        // : cancelling the in-flight command preserves the pending queue — dispatch-next-or-idle
        // may still dispatch a queued request after the cancelled command's terminal frame.
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // dispatched
        s.submit("second");
        s.submit("third"); // queued

        requireCancel(s.cancel());

        assertEquals(ClientSession.State.RUNNING, s.state()); // await the command's terminal frame
        assertEquals(List.of("second", "third"), s.pendingQueue()); // queue preserved
        assertNull(s.promptView().activePrompt());
    }

    @Test
    void cancelFromPromptedReturnsCancelClearsPromptAndStaysRunning() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first");
        s.onFrame(new IpcFrame.Prompt("pw", false));

        requireCancel(s.cancel());

        assertNull(s.promptView().activePrompt());
        assertEquals(ClientSession.State.RUNNING, s.state());
    }

    // ═══ inbound: IDLE ═══════════════════════════════════════════════

    @Test
    void idleRejectsOrphanDeltaWithoutDisplay() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        IpcFrame.ClientFrame f = s.onFrame(new IpcFrame.Delta("chunk"));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("delta:")));
    }

    @Test
    void idleRejectsOrphanProgressWithoutDisplay() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        IpcFrame.ClientFrame f = s.onFrame(new IpcFrame.Progress("loading", 50));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("progress:")));
    }

    @Test
    void idleRejectsOrphanPromptWithoutDisplay() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        IpcFrame.ClientFrame f = s.onFrame(new IpcFrame.Prompt("pw", false));

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

        IpcFrame.ClientFrame f =
                s.onFrame(new IpcFrame.Done(Map.of(IpcMeta.USERNAME, "alice"), null));

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

        IpcFrame.ClientFrame f = s.onFrame(IpcFrame.Error.ofError("late"));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertTrue(v.events.contains("error:Error: late"));
    }

    // ═══ inbound: RUNNING ════════════════════════════════════════════

    @Test
    void runningDeltaDisplays() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // RUNNING

        s.onFrame(new IpcFrame.Delta("chunk"));

        assertTrue(v.events.contains("delta:chunk"));
    }

    @Test
    void runningThoughtDeltaRoutesToOnThought() {
        // A thought-kind Delta must route to onThought (distinct rendering), NOT to onDelta, so the
        // terminal can dim the agent's reasoning separately from user-facing message prose.
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // RUNNING

        s.onFrame(IpcFrame.Delta.thought("reasoning chunk"));

        assertTrue(v.events.contains("thought:reasoning chunk"));
        assertFalse(
                v.events.contains("delta:reasoning chunk"),
                "a thought-kind Delta must NOT also fire onDelta");
    }

    @Test
    void runningProgressDisplays() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // RUNNING

        s.onFrame(new IpcFrame.Progress("loading", 50));

        assertTrue(v.events.stream().anyMatch(e -> e.equals("progress:  ⏳ loading")));
    }

    @Test
    void runningPromptTransitionsToPrompted() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // RUNNING

        s.onFrame(new IpcFrame.Prompt("password", true));

        assertEquals(ClientSession.State.PROMPTED, s.state());
        IpcFrame.Prompt prompt = requirePrompt(s.promptView().activePrompt());
        assertEquals("password", prompt.content());
        assertTrue(v.events.contains("prompted"));
        assertTrue(v.events.contains("prompt:password"));
    }

    @Test
    void runningDoneAppliesMetaAndDispatchesNext() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // dispatched
        s.submit("second"); // enqueued

        v.events.clear();
        IpcFrame.ClientFrame f =
                requireFrame(s.onFrame(new IpcFrame.Done(Map.of(IpcMeta.USERNAME, "alice"), null)));

        IpcFrame.Request request = assertInstanceOf(nonNullClass(IpcFrame.Request.class), f);
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

        s.submit("hello");

        assertTrue(v.events.contains("dispatched:hello"));
    }

    @Test
    void enqueuedCommandDoesNotEchoUntilDispatched() {
        // A command enqueued while RUNNING must NOT echo "thinking…" when merely typed — it echoes
        // onCommandDispatched only when it is actually dispatched from the queue.
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // dispatched → echoes
        v.events.clear();

        s.submit("second"); // enqueued while RUNNING — no echo yet
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("dispatched:")));
        assertEquals(ClientSession.State.RUNNING, s.state());
        assertEquals(List.of("second"), s.pendingQueue());

        v.events.clear();
        s.onFrame(new IpcFrame.Done(Map.of(), null)); // terminal → dispatch "second"
        assertTrue(v.events.contains("dispatched:second"));
    }

    @Test
    void runningDoneWithEmptyQueueGoesIdle() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // dispatched

        v.events.clear();
        IpcFrame.ClientFrame f = s.onFrame(new IpcFrame.Done(Map.of(), null));

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
        s.submit("first"); // dispatched
        s.submit("second"); // queued

        v.events.clear();
        IpcFrame.ClientFrame f = requireFrame(s.onFrame(IpcFrame.Error.ofError("boom")));

        IpcFrame.Request request = assertInstanceOf(nonNullClass(IpcFrame.Request.class), f);
        assertEquals("second", request.raw());
        assertTrue(v.events.contains("error:Error: boom"));
    }

    // ═══ inbound: PROMPTED ═══════════════════════════════════════════

    @Test
    void promptedDeltaDisplays() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first");
        s.onFrame(new IpcFrame.Prompt("pw", false)); // PROMPTED

        s.onFrame(new IpcFrame.Delta("chunk"));

        assertTrue(v.events.contains("delta:chunk"));
        assertEquals(ClientSession.State.PROMPTED, s.state());
    }

    @Test
    void promptedPromptReplacesPrompt() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first");
        s.onFrame(new IpcFrame.Prompt("pw1", false)); // PROMPTED

        s.onFrame(new IpcFrame.Prompt("pw2", false)); // replace

        assertEquals(ClientSession.State.PROMPTED, s.state());
        IpcFrame.Prompt prompt = requirePrompt(s.promptView().activePrompt());
        assertEquals("pw2", prompt.content());
        // A replace does not re-fire the onPrompted transition signal (already prompted).
        long promptedCount = v.events.stream().filter("prompted"::equals).count();
        assertEquals(1, promptedCount);
    }

    @Test
    void promptedDoneAppliesMetaClearsPromptAndDispatchesNext() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first");
        s.submit("second"); // queued
        s.onFrame(new IpcFrame.Prompt("pw", false)); // PROMPTED
        requirePrompt(s.promptView().activePrompt());

        v.events.clear();
        IpcFrame.ClientFrame f =
                requireFrame(s.onFrame(new IpcFrame.Done(Map.of(IpcMeta.USERNAME, "alice"), null)));

        IpcFrame.Request request = assertInstanceOf(nonNullClass(IpcFrame.Request.class), f);
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
        s.submit("first");
        s.onFrame(new IpcFrame.Prompt("pw", false)); // PROMPTED
        requirePrompt(s.promptView().activePrompt());

        IpcFrame.ClientFrame f = s.onFrame(new IpcFrame.Done(Map.of(), null));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertNull(s.promptView().activePrompt());
    }

    @Test
    void promptedErrorClearsPromptDisplaysAndDispatchesNext() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first");
        s.submit("second"); // queued
        s.onFrame(new IpcFrame.Prompt("pw", false)); // PROMPTED
        requirePrompt(s.promptView().activePrompt());

        IpcFrame.ClientFrame f = requireFrame(s.onFrame(IpcFrame.Error.ofError("boom")));

        IpcFrame.Request request = assertInstanceOf(nonNullClass(IpcFrame.Request.class), f);
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
        s.submit("first");
        s.onFrame(new IpcFrame.Prompt("pw", false)); // PROMPTED
        requirePrompt(s.promptView().activePrompt());

        IpcFrame.ClientFrame f = s.onFrame(new IpcFrame.Terminate("bye now"));

        assertNull(f); // no dispatch on terminate
        assertTrue(v.events.contains("terminate:bye now"));
        assertNull(s.promptView().activePrompt()); // cleared on teardown
    }

    // ═══ meta application ═════════════════════════════════════════════════

    @Test
    void applyMetaUpdatesSnapshot() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first");

        s.onFrame(
                new IpcFrame.Done(
                        Map.of(
                                IpcMeta.USERNAME,
                                "alice",
                                IpcMeta.TURN_NUMBER,
                                5,
                                IpcMeta.SESSION,
                                "sess1"),
                        null));

        ClientSession.SessionMeta m = s.snapshot();
        assertEquals("alice", m.username());
        assertEquals(5, m.turnCount());
        assertEquals("sess1", m.sessionId());
        assertTrue(v.events.contains("meta:alice/5/sess1"));
    }

    @Test
    void clearSessionResetsMeta() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first");
        s.onFrame(
                new IpcFrame.Done(
                        Map.of(
                                IpcMeta.USERNAME,
                                "alice",
                                IpcMeta.TURN_NUMBER,
                                5,
                                IpcMeta.SESSION,
                                "s1"),
                        null));

        s.onFrame(new IpcFrame.Done(Map.of(IpcMeta.CLEAR_SESSION, true), null));

        ClientSession.SessionMeta m = s.snapshot();
        assertNull(m.username());
        assertEquals(0, m.turnCount());
        assertNull(m.sessionId());
    }

    @Test
    void unchangedMetaDoesNotFireMetaChanged() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first");
        s.onFrame(new IpcFrame.Done(Map.of(IpcMeta.USERNAME, "alice"), null));
        v.events.clear();

        s.onFrame(new IpcFrame.Done(Map.of(IpcMeta.USERNAME, "alice"), null)); // same username

        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("meta:")));
    }

    // ═══ snapshots & concurrency ═══════════════════════════════════════════

    @Test
    void promptViewCapturesStateAndPromptAtomically() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // RUNNING

        s.onFrame(new IpcFrame.Prompt("password", true));
        ClientSession.PromptView prompted = s.promptView();
        assertEquals(ClientSession.State.PROMPTED, prompted.state());
        IpcFrame.Prompt activePrompt = requirePrompt(prompted.activePrompt());
        assertEquals("password", activePrompt.content());
        assertEquals(ClientSession.State.PROMPTED, prompted.state()); // unchanged by the read

        s.submit("secret"); // PROMPTED → RUNNING (Input sent)
        ClientSession.PromptView running = s.promptView();
        assertEquals(ClientSession.State.RUNNING, running.state());
        assertNull(running.activePrompt()); // cleared on submit, same moment as the state
    }

    @Test
    void pendingQueueSnapshotIsImmutableCopy() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("a"); // dispatched
        s.submit("b");
        s.submit("c");

        List<String> q = s.pendingQueue();

        assertEquals(List.of("b", "c"), q);
        assertThrows(nonNullClass(UnsupportedOperationException.class), () -> q.add("d"));
    }

    @Test
    void statusViewCapturesUsernameAndQueueAtomically() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // dispatched → RUNNING
        s.submit("second"); // enqueued
        s.submit("third"); // enqueued → queue = [second, third]
        // The Done sets the username AND dispatches the next queued request ("second") — both in
        // one transition. statusView must reflect both effects from the same moment.
        s.onFrame(new IpcFrame.Done(Map.of(IpcMeta.USERNAME, "alice"), null));

        ClientSession.StatusView view = s.statusView();
        assertEquals("alice", view.username()); // username just set by the Done
        assertEquals(List.of("third"), view.pending()); // "second" dispatched, "third" remains
        assertThrows(
                nonNullClass(UnsupportedOperationException.class), () -> view.pending().add("x"));
    }

    @Test
    void promptDuringSubmitIsSerializedByTheMonitor() throws InterruptedException {
        // A Prompt arriving concurrently with submit must not corrupt state or deadlock — the
        // self-owned monitor serializes them. Outcome is order-dependent but always valid.
        for (int i = 0; i < 200; i++) {
            RecordingView v = new RecordingView();
            ClientSession s = new ClientSession(v);
            s.submit("first"); // RUNNING

            CountDownLatch bothStarted = new CountDownLatch(1);
            Thread a = new Thread(() -> s.submit("reply"));
            Thread b =
                    new Thread(
                            () -> {
                                try {
                                    bothStarted.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                s.onFrame(new IpcFrame.Prompt("pw", false));
                            });

            a.start();
            bothStarted.countDown();
            b.start();
            a.join();
            b.join();

            // Never IDLE (either still RUNNING, or PROMPTED), and no exception propagated.
            assertNotEquals(ClientSession.State.IDLE, s.state());
        }
    }

    private static IpcFrame.@NonNull ClientFrame requireFrame(IpcFrame.ClientFrame frame) {
        if (frame == null) {
            throw new AssertionError("Expected a client frame");
        }
        return frame;
    }

    private static IpcFrame.@NonNull Prompt requirePrompt(IpcFrame.Prompt prompt) {
        if (prompt == null) {
            throw new AssertionError("Expected an active prompt");
        }
        return prompt;
    }

    private static IpcFrame.@NonNull Cancel requireCancel(IpcFrame.Cancel cancel) {
        if (cancel == null) {
            throw new AssertionError("Expected a cancel frame");
        }
        return cancel;
    }

    private static IpcFrame.@NonNull VetoPayload requireVetoPayload(IpcFrame.VetoPayload payload) {
        if (payload == null) {
            throw new AssertionError("The veto payload should be exposed in the prompt view");
        }
        return payload;
    }

    private static <T extends @NonNull Object> @NonNull Class<T> nonNullClass(Class<T> type) {
        if (type == null) {
            throw new AssertionError("Expected a class token");
        }
        return type;
    }
}
