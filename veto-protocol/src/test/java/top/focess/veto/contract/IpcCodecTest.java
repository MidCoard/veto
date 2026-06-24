package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IpcCodec} — the pure JSON codec for {@link IpcFrame}. Covers round-trip of
 * every frame type, the {@link IpcFrame.Unknown} fallback for unrecognized types, and
 * null-on-malformed input.
 */
class IpcCodecTest {

    @Test
    void roundTripsEveryFrameType() {
        IpcFrame[] frames = {
            new IpcFrame.Hello(IpcFrame.PROTOCOL_VERSION, 7L),
            new IpcFrame.Welcome(IpcFrame.PROTOCOL_VERSION, 7L),
            new IpcFrame.Request("do the thing"),
            new IpcFrame.Complete("/log", 11L),
            new IpcFrame.Hint("/login ", 12L),
            new IpcFrame.Input("secret-value"),
            new IpcFrame.Cancel(),
            new IpcFrame.Bye(),
            new IpcFrame.Heartbeat(),
            new IpcFrame.CompleteResult(
                    List.of(
                            new IpcFrame.Completion("/login", "sign in", "auth"),
                            new IpcFrame.Completion("/status", null, null)),
                    11L),
            new IpcFrame.HintResult(new IpcFrame.HintInfo("<user>", "enter username"), 12L),
            new IpcFrame.Done(Map.of("username", "alice", "turnNumber", 3), "ok"),
            new IpcFrame.Error("boom", 9L),
            new IpcFrame.Delta("chunk"),
            new IpcFrame.Progress("working", 42),
            new IpcFrame.Progress("working", IpcFrame.Progress.INDETERMINATE),
            new IpcFrame.Prompt("password:", Map.of("mask", true)),
            new IpcFrame.Terminate("bye"),
        };

        for (IpcFrame frame : frames) {
            byte[] encoded = IpcCodec.encode(frame);
            assertNotNull(encoded, "encode returned null for " + frame.getClass().getSimpleName());
            IpcFrame decoded = IpcCodec.decode(encoded);
            assertNotNull(decoded, "decode returned null for " + frame.getClass().getSimpleName());
            assertEquals(
                    frame.getClass(),
                    decoded.getClass(),
                    "type mismatch for " + frame.getClass().getSimpleName());
            assertEquals(
                    frame, decoded, "round-trip not equal for " + frame.getClass().getSimpleName());
        }
    }

    @Test
    void encodeStringMatchesEncodeBytes() {
        IpcFrame frame = new IpcFrame.Delta("hello");
        String json = IpcCodec.encodeString(frame);
        assertEquals(new String(IpcCodec.encode(frame), StandardCharsets.UTF_8), json);
    }

    @Test
    void unknownTypeDegradesToUnknown() {
        // An unrecognized "type" discriminator must not throw — it degrades to IpcFrame.Unknown so
        // a newer-protocol peer is logged-and-skipped rather than rejected.
        IpcFrame decoded = IpcCodec.decode("{\"type\":\"some_future_frame\",\"content\":\"x\"}");
        assertNotNull(decoded, "unknown type should degrade to Unknown, not null");
        assertInstanceOf(IpcFrame.Unknown.class, decoded);
        // The discriminator is bound (via visible=true); the unrecognized body is intentionally not
        // captured — only the type is kept, so handlers can log "unknown type 'X'" and skip.
        assertEquals("some_future_frame", ((IpcFrame.Unknown) decoded).type());
    }

    @Test
    void malformedPayloadReturnsNull() {
        assertNull(IpcCodec.decode("not json at all"));
        assertNull(IpcCodec.decode("{"));
    }

    @Test
    void decodeStringOverloadMatchesBytes() {
        IpcFrame frame = new IpcFrame.Delta("x");
        String json = IpcCodec.encodeString(frame);
        assertEquals(IpcCodec.decode(json), IpcCodec.decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void handshakeFramesRoundTripWithOnlyVersionAndSeq() {
        // Handshake frames are pure: version + seq, no auth (auth is a transport/tunnel concern,
        // not a frame concern).
        String helloJson = IpcCodec.encodeString(new IpcFrame.Hello(IpcFrame.PROTOCOL_VERSION, 1L));
        assertFalse(helloJson.contains("\"auth\""));
        IpcFrame.Hello helloBack = (IpcFrame.Hello) IpcCodec.decode(helloJson);
        assertEquals(IpcFrame.PROTOCOL_VERSION, helloBack.version());
        assertEquals(1L, helloBack.seq());

        IpcFrame.Welcome w = new IpcFrame.Welcome(IpcFrame.PROTOCOL_VERSION, 9L);
        IpcFrame.Welcome back = (IpcFrame.Welcome) IpcCodec.decode(IpcCodec.encode(w));
        assertEquals(IpcFrame.PROTOCOL_VERSION, back.version());
        assertEquals(9L, back.seq());
    }

    @Test
    void doneTypedAccessorsReadMetaSafely() {
        IpcFrame.Done done =
                new IpcFrame.Done(
                        Map.of("username", "alice", "turnNumber", 7, "cancelled", true), "ok");
        assertEquals("alice", done.username());
        assertEquals(7, done.turnNumber());
        assertTrue(done.cancelled());
        assertFalse(done.clearSession());

        // Absent keys degrade to defaults, not exceptions.
        IpcFrame.Done empty = new IpcFrame.Done(Map.of(), null);
        assertNull(empty.username());
        assertEquals(-1, empty.turnNumber());
        assertFalse(empty.cancelled());
    }

    @Test
    void promptMaskAccessorReadsMetaSafely() {
        IpcFrame.Prompt masked = new IpcFrame.Prompt("password:", Map.of("mask", true));
        assertTrue(masked.mask());
        IpcFrame.Prompt plain = new IpcFrame.Prompt("name:", Map.of());
        assertFalse(plain.mask());
    }
}
