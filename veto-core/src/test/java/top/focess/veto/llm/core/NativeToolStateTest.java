package top.focess.veto.llm.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.llm.NativeToolState;

class NativeToolStateTest {
    @Test
    void persistsProviderAndVersionAndReadsLegacyGeminiState() {
        var state = new NativeToolState("gemini-model", "batch", "{}", 0);
        var payload = state.toPayload();
        assertEquals("GEMINI", payload.get("provider"));
        assertEquals(1, payload.get("version"));
        assertEquals(state, NativeToolState.fromPayload(payload));
        var legacy =
                NativeToolState.fromPayload(
                        Map.of(
                                "model",
                                "gemini-model",
                                "batch",
                                "batch",
                                "partsJson",
                                "{}",
                                "position",
                                0));
        assertEquals(state, legacy);
        assertNull(
                NativeToolState.fromPayload(
                        Map.of(
                                "model",
                                "gemini-model",
                                "batch",
                                "batch",
                                "partsJson",
                                "{}",
                                "version",
                                "unknown")));
    }

    @Test
    void signedStateCannotBeReplayedByAnotherProviderModelOrVersion() {
        assertTrue(
                new NativeToolState("gemini-model", "batch", "{}", 0)
                        .supports("GEMINI", "gemini-model"));
        assertFalse(
                new NativeToolState("OTHER", 1, "gemini-model", "batch", "{}", 0)
                        .supports("GEMINI", "gemini-model"));
        assertFalse(
                new NativeToolState("GEMINI", 2, "gemini-model", "batch", "{}", 0)
                        .supports("GEMINI", "gemini-model"));
        assertFalse(
                new NativeToolState("gemini-model", "batch", "{}", 0)
                        .supports("GEMINI", "other-model"));
    }
}
