package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SeqCorrelator} — no socket, no I/O, pure correlation logic. */
class SeqCorrelatorTest {

    @Test
    void nextIsMonotonicFromOne() {
        SeqCorrelator c = new SeqCorrelator();
        assertEquals(1L, c.next());
        assertEquals(2L, c.next());
        assertEquals(3L, c.next());
    }

    @Test
    void handshakeAndFirstRequestDoNotShareSeq() {
        // The bug being prevented: the handshake used to hardcode seq=1 while next() also started
        // at
        // 1, so the first user request reused the handshake's seq. With SeqCorrelator as the single
        // source, the handshake draws 1 and the first request draws 2.
        SeqCorrelator c = new SeqCorrelator();
        long handshakeSeq = c.next();
        long firstRequestSeq = c.next();
        assertEquals(1L, handshakeSeq);
        assertEquals(2L, firstRequestSeq);
        assertNotEquals(handshakeSeq, firstRequestSeq);
    }

    @Test
    void deliverRoutesToAwait() throws InterruptedException {
        SeqCorrelator c = new SeqCorrelator();
        long seq = c.next();
        c.register(seq);
        IpcFrame.CompleteResult result =
                new IpcFrame.CompleteResult(
                        List.of(new IpcFrame.Completion("/x", null, null)), seq);
        c.deliver(result);
        IpcFrame.@NonNull SeqResponse got = requireResponse(c.await(seq, 1, TimeUnit.SECONDS));
        assertSame(result, got);
    }

    @Test
    void awaitTimesOutAndRemovesHandler() throws InterruptedException {
        SeqCorrelator c = new SeqCorrelator();
        long seq = c.next();
        c.register(seq);
        assertNull(c.await(seq, 50, TimeUnit.MILLISECONDS));
        // Handler is gone after a timed-out await: a late deliver is dropped, a second await
        // returns
        // null immediately.
        c.deliver(new IpcFrame.CompleteResult(List.of(), seq));
        assertNull(c.await(seq, 50, TimeUnit.MILLISECONDS));
    }

    @Test
    void awaitWithoutRegisterReturnsNull() throws InterruptedException {
        SeqCorrelator c = new SeqCorrelator();
        assertNull(c.await(999L, 50, TimeUnit.MILLISECONDS));
    }

    @Test
    void deliverWithNoHandlerIsDroppedNotThrown() {
        SeqCorrelator c = new SeqCorrelator();
        // No handler for seq=42 — must not throw.
        c.deliver(new IpcFrame.CompleteResult(List.of(), 42L));
    }

    @Test
    void deliverIgnoresSeqZero() throws InterruptedException {
        // seq=0 responses (e.g. a streaming Error in reply to a seq-less Request) are not
        // correlated — they must never match a registered handler, even seq=0.
        SeqCorrelator c = new SeqCorrelator();
        c.register(0L);
        c.deliver(IpcFrame.Error.ofError("streaming error"));
        assertNull(c.await(0L, 50, TimeUnit.MILLISECONDS));
    }

    @Test
    void discardFreesHandler() throws InterruptedException {
        SeqCorrelator c = new SeqCorrelator();
        long seq = c.next();
        c.register(seq);
        c.discard(seq);
        // After discard, deliver is dropped and await returns null.
        c.deliver(new IpcFrame.CompleteResult(List.of(), seq));
        assertNull(c.await(seq, 50, TimeUnit.MILLISECONDS));
    }

    private static IpcFrame.@NonNull SeqResponse requireResponse(IpcFrame.SeqResponse response) {
        if (response != null) {
            return response;
        }
        throw new AssertionError("expected correlated response");
    }
}
