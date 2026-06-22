package top.focess.veto.client.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;

/**
 * Direct, deterministic tests of {@link ClientSession}'s protocol logic. The session is
 * transport-agnostic (fed frames via {@link ClientSession#onFrame}, returns frames via {@link
 * ClientSession#submit}/{@link ClientSession#cancel}), so no ZMQ or {@link
 * top.focess.veto.contract.IpcClient} is needed — a recording {@link ClientView} captures every
 * render event.
 */
class ClientSessionTest {

    /** A ClientView that records every callback as a string for assertion. */
    private static final class RecordingView implements ClientView {
        final List<String> events = Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void onDelta(String content) {
            events.add("delta:" + content);
        }

        @Override
        public void onProgress(StyledText content) {
            events.add("progress:" + content.text());
        }

        @Override
        public void onPrompt(IpcFrame.Prompt prompt) {
            events.add("prompt:" + prompt.content());
        }

        @Override
        public void onDone(String content) {
            events.add("done:" + content);
        }

        @Override
        public void onError(StyledText content) {
            events.add("error:" + content.text());
        }

        @Override
        public void onTerminate(StyledText content) {
            events.add("terminate:" + content.text());
        }

        @Override
        public void onIdle() {
            events.add("idle");
        }

        @Override
        public void onAwaiting() {
            events.add("awaiting");
        }

        @Override
        public void onPrompted() {
            events.add("prompted");
        }

        @Override
        public void onMetaChanged(ClientSession.SessionMeta meta) {
            events.add("meta:" + meta.username() + "/" + meta.turnCount() + "/" + meta.sessionId());
        }
    }

    @Test
    void submitFromIdleDispatchesRequestAndTransitionsToAwaiting() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        IpcFrame.ClientFrame f = s.submit("hello");

        assertInstanceOf(IpcFrame.Request.class, f);
        assertEquals("hello", ((IpcFrame.Request) f).raw());
        assertEquals(ClientSession.State.AWAITING_RESPONSE, s.state());
        assertTrue(v.events.contains("awaiting"));
    }

    @Test
    void submitWhileAwaitingEnqueuesAndReturnsNull() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // IDLE → AWAITING, dispatched

        v.events.clear();
        IpcFrame.ClientFrame f = s.submit("second"); // AWAITING → enqueue

        assertNull(f);
        assertEquals(ClientSession.State.AWAITING_RESPONSE, s.state());
        assertEquals(List.of("second"), s.pendingQueue());
    }

    @Test
    void doneDispatchesNextQueuedRequest() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // dispatched
        s.submit("second"); // enqueued

        v.events.clear();
        IpcFrame.ClientFrame f = s.onFrame(new IpcFrame.Done(Map.of(), "done1"));

        assertInstanceOf(IpcFrame.Request.class, f);
        assertEquals("second", ((IpcFrame.Request) f).raw());
        assertEquals(ClientSession.State.AWAITING_RESPONSE, s.state());
        assertTrue(v.events.contains("done:done1"));
        assertTrue(v.events.contains("awaiting"));
        assertTrue(s.pendingQueue().isEmpty());
    }

    @Test
    void doneWithEmptyQueueGoesIdle() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // dispatched

        v.events.clear();
        IpcFrame.ClientFrame f = s.onFrame(new IpcFrame.Done(Map.of(), null));

        assertNull(f);
        assertEquals(ClientSession.State.IDLE, s.state());
        assertTrue(v.events.contains("idle"));
        // Null content → no onDone event.
        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("done:")));
    }

    @Test
    void promptThenSubmitReturnsInput() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // AWAITING

        s.onFrame(new IpcFrame.Prompt("password", Map.of(IpcMeta.MASK, true)));

        assertEquals(ClientSession.State.PROMPTED, s.state());
        assertNotNull(s.activePrompt());
        v.events.clear();

        IpcFrame.ClientFrame f = s.submit("secret");

        assertInstanceOf(IpcFrame.Input.class, f);
        assertEquals("secret", ((IpcFrame.Input) f).raw());
        assertEquals(ClientSession.State.AWAITING_RESPONSE, s.state());
        assertNull(s.activePrompt());
    }

    @Test
    void cancelFromIdleReturnsNullAsShutdownSignal() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        assertNull(s.cancel());
        assertEquals(ClientSession.State.IDLE, s.state());
    }

    @Test
    void cancelFromAwaitingReturnsCancelAndClearsQueue() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // dispatched
        s.submit("second");
        s.submit("third"); // queued

        IpcFrame.Cancel c = s.cancel();

        assertNotNull(c);
        assertEquals(ClientSession.State.AWAITING_RESPONSE, s.state()); // await cancel-ack Done
        assertTrue(s.pendingQueue().isEmpty());
        assertNull(s.activePrompt());
    }

    @Test
    void cancelFromPromptedReturnsCancelAndClearsPrompt() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first");
        s.onFrame(new IpcFrame.Prompt("pw", Map.of()));

        IpcFrame.Cancel c = s.cancel();

        assertNotNull(c);
        assertNull(s.activePrompt());
        assertEquals(ClientSession.State.AWAITING_RESPONSE, s.state());
    }

    @Test
    void applyMetaUpdatesSnapshot() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

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
        s.onFrame(new IpcFrame.Done(Map.of(IpcMeta.USERNAME, "alice"), null));
        v.events.clear();

        s.onFrame(new IpcFrame.Done(Map.of(IpcMeta.USERNAME, "alice"), null)); // same username

        assertFalse(v.events.stream().anyMatch(e -> e.startsWith("meta:")));
    }

    @Test
    void onDeltaFiresOnDelta() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        s.onFrame(new IpcFrame.Delta("chunk"));

        assertTrue(v.events.contains("delta:chunk"));
    }

    @Test
    void onProgressFiresMutedWithSpinner() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);

        s.onFrame(new IpcFrame.Progress("loading", 50));

        assertTrue(v.events.stream().anyMatch(e -> e.equals("progress:  ⏳ loading")));
    }

    @Test
    void onErrorFiresErrorAndDispatchesNext() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // dispatched
        s.submit("second"); // queued

        v.events.clear();
        IpcFrame.ClientFrame f = s.onFrame(IpcFrame.Error.ofError("boom"));

        assertInstanceOf(IpcFrame.Request.class, f);
        assertEquals("second", ((IpcFrame.Request) f).raw());
        assertTrue(v.events.contains("error:Error: boom"));
    }

    @Test
    void onTerminateFiresTerminateWithoutStateChange() {
        RecordingView v = new RecordingView();
        ClientSession s = new ClientSession(v);
        s.submit("first"); // AWAITING

        IpcFrame.ClientFrame f = s.onFrame(new IpcFrame.Terminate("bye now"));

        assertNull(f); // no dispatch on terminate
        assertEquals(ClientSession.State.AWAITING_RESPONSE, s.state()); // unchanged
        assertTrue(v.events.contains("terminate:bye now"));
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
        assertThrows(UnsupportedOperationException.class, () -> q.add("d"));
    }

    @Test
    void promptDuringSubmitIsSerializedByTheMonitor() throws InterruptedException {
        // A Prompt arriving concurrently with submit must not corrupt state or deadlock — the
        // self-owned monitor serializes them. Outcome is order-dependent but always valid.
        for (int i = 0; i < 200; i++) {
            RecordingView v = new RecordingView();
            ClientSession s = new ClientSession(v);
            s.submit("first"); // AWAITING

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
                                s.onFrame(new IpcFrame.Prompt("pw", Map.of()));
                            });

            a.start();
            bothStarted.countDown();
            b.start();
            a.join();
            b.join();

            // Never IDLE (either still AWAITING, or PROMPTED), and no exception propagated.
            assertNotEquals(ClientSession.State.IDLE, s.state());
        }
    }
}
