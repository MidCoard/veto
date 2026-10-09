package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.contract.ContractTestSupport.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FrameCodec} — the pure JSON codec for {@link Frame}. Covers round-trip of
 * every frame type, rejection of unknown types, and null-on-malformed input.
 */
class FrameCodecTest {

    @Test
    void roundTripsEveryFrameType() {
        String userDir = System.getProperty("user.dir");
        if (userDir == null) {
            throw new AssertionError("user.dir system property is unavailable");
        }
        Frame[] frames = {
            new Frame.Hello(Frame.PROTOCOL_VERSION, 7L, Version.parse("1.0.0-SNAPSHOT"), userDir),
            new Frame.Welcome(Frame.PROTOCOL_VERSION, 7L, Version.parse("1.2.3")),
            new Frame.Request("do the thing"),
            new Frame.Complete("/log", 11L),
            new Frame.Hint("/login ", 12L),
            new Frame.Input("secret-value"),
            new Frame.Cancel(),
            new Frame.Bye(),
            new Frame.Heartbeat(0),
            new Frame.CompleteResult(
                    List.of(
                            new Frame.Completion("/login", "sign in", "auth"),
                            new Frame.Completion("/status", null, null)),
                    11L),
            new Frame.HintResult(new Frame.HintInfo("<user>", "enter username"), 12L),
            new Frame.Done(Map.of("username", "alice", "turnNumber", 3), "ok"),
            new Frame.Error("boom", 9L),
            EventFrame.command("chunk"),
            new Frame.Progress("working", 42),
            new Frame.Progress("working", Frame.Progress.INDETERMINATE),
            new Frame.Prompt("password:", true),
            new Frame.Terminate("bye"),
        };

        for (Frame frame : frames) {
            byte[] encoded = FrameCodec.encode(frame);
            Frame decoded = FrameCodec.decode(encoded);
            if (decoded == null) {
                throw new AssertionError(
                        "decode returned null for " + frame.getClass().getSimpleName());
            }
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
        Frame frame = EventFrame.command("hello");
        String json = FrameCodec.encodeString(frame);
        assertEquals(new String(FrameCodec.encode(frame), StandardCharsets.UTF_8), json);
    }

    @Test
    void obsoleteAndUnknownTypesAreRejected() {
        assertNull(FrameCodec.decode("{\"type\":\"some_future_frame\",\"content\":\"x\"}"));
        assertNull(FrameCodec.decode("{\"type\":\"delta\",\"content\":\"x\"}"));
    }

    @Test
    void malformedPayloadReturnsNull() {
        assertNull(FrameCodec.decode("not json at all"));
        assertNull(FrameCodec.decode("{"));
    }

    @Test
    void decodeStringOverloadMatchesBytes() {
        Frame frame = EventFrame.command("x");
        String json = FrameCodec.encodeString(frame);
        assertEquals(
                FrameCodec.decode(json), FrameCodec.decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void handshakeFramesRoundTripWithOnlyVersionAndSeq() {
        // Handshake frames are pure: version + seq, no auth (auth is a transport/tunnel concern,
        // not a frame concern).
        String userDir = System.getProperty("user.dir");
        if (userDir == null) {
            throw new AssertionError("user.dir system property is unavailable");
        }
        String helloJson =
                FrameCodec.encodeString(
                        new Frame.Hello(
                                Frame.PROTOCOL_VERSION,
                                1L,
                                Version.parse("1.0.0-SNAPSHOT"),
                                userDir));
        assertFalse(helloJson.contains("\"auth\""));
        Frame decodedHello = FrameCodec.decode(helloJson);
        if (decodedHello == null) {
            throw new AssertionError("Hello deserialization returned null");
        }
        Frame.Hello helloBack = assertInstanceOf(Frame.Hello.class, decodedHello);
        assertEquals(Frame.PROTOCOL_VERSION, helloBack.version());
        assertEquals(1L, helloBack.seq());
        assertEquals(Version.parse("1.0.0-SNAPSHOT"), helloBack.productVersion());

        Frame.Welcome w = new Frame.Welcome(Frame.PROTOCOL_VERSION, 9L, Version.parse("1.2.3"));
        Frame decodedWelcome = FrameCodec.decode(FrameCodec.encode(w));
        if (decodedWelcome == null) {
            throw new AssertionError("Welcome deserialization returned null");
        }
        Frame.Welcome back = assertInstanceOf(Frame.Welcome.class, decodedWelcome);
        assertEquals(Frame.PROTOCOL_VERSION, back.version());
        assertEquals(9L, back.seq());
        assertEquals(Version.parse("1.2.3"), back.productVersion());
    }

    @Test
    void doneTypedAccessorsReadMetaSafely() {
        Frame.Done done =
                new Frame.Done(
                        Map.of("username", "alice", "turnNumber", 7, "cancelled", true), "ok");
        assertEquals("alice", done.username());
        assertEquals(7, done.turnNumber());
        assertTrue(done.cancelled());
        assertFalse(done.clearSession());

        // Absent keys degrade to defaults, not exceptions.
        Frame.Done empty = new Frame.Done(Map.of(), null);
        assertNull(empty.username());
        assertEquals(-1, empty.turnNumber());
        assertFalse(empty.cancelled());
    }

    @Test
    void promptMaskAccessor() {
        Frame.Prompt masked = new Frame.Prompt("password:", true);
        assertTrue(masked.mask());
        Frame.Prompt plain = new Frame.Prompt("name:", false);
        assertFalse(plain.mask());
    }
}
