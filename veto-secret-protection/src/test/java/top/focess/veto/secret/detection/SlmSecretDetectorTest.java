package top.focess.veto.secret.detection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.secret.api.SecretDetectionModel;

/** SLM-primary detection with deterministic degraded fallback behind a stub model port. */
@SuppressWarnings("nullness") // Cross-module class literals read as nullable.
class SlmSecretDetectorTest {
    private static @NonNull SecretDetectionModel model(
            boolean available, @NonNull Optional<String> response) {
        return new SecretDetectionModel() {
            @Override
            public boolean isAvailable() {
                return available;
            }

            @Override
            public @NonNull Optional<String> complete(
                    @NonNull String source, @NonNull Map<String, ?> data) {
                return response;
            }
        };
    }

    @Test
    void validModelOutputProducesSpansForEveryOccurrence() {
        var detector = new SlmSecretDetector(model(true, Optional.of("[\"synthetic-token\"]")));
        String text = "first synthetic-token then synthetic-token";
        var matches = detector.detect(text);
        assertEquals(2, matches.size());
        assertEquals("slm-detected", matches.getFirst().category());
        assertEquals(
                "first [REDACTED_SLM_DETECTED] then [REDACTED_SLM_DETECTED]", detector.mask(text));
    }

    @Test
    void modelFindsSecretsTheDeterministicPatternsMiss() {
        var detector = new SlmSecretDetector(model(true, Optional.of("[\"wEk7-qR9\"]")));
        String masked = detector.mask("note the password is wEk7-qR9 today");
        assertEquals("note the password is [REDACTED_SLM_DETECTED] today", masked);
    }

    @Test
    void wholeStructuredDocumentEchoIsNotTreatedAsOneSecret() {
        String clean = "{\"action\":\"compile\",\"files\":[\"main.cpp\"]}";
        var cleanDetector =
                new SlmSecretDetector(model(true, Optional.of("[" + quote(clean) + "]")));
        assertEquals(clean, cleanDetector.mask(clean));

        String sensitive = "{\"apiKey\":\"abcdefghijklmnopqrstuvwxyz0123456789ABCDEF\"}";
        var sensitiveDetector =
                new SlmSecretDetector(model(true, Optional.of("[" + quote(sensitive) + "]")));
        String masked = sensitiveDetector.mask(sensitive);
        assertFalse(masked.contains("abcdefghijklmnopqrstuvwxyz0123456789ABCDEF"));
        assertTrue(masked.contains("REDACTED"));
    }

    @Test
    void blanketStructuredTokenClassificationFallsBackToDeterministicMatches() {
        String clean = "{\"operation\":\"build\",\"targets\":[\"application.cc\"]}";
        var cleanDetector =
                new SlmSecretDetector(
                        model(
                                true,
                                Optional.of(
                                        "[\"operation\",\"build\",\"targets\",\"application.cc\"]")));
        assertEquals(clean, cleanDetector.mask(clean));

        String credential =
                "{\"job\":{\"kind\":\"publish\",\"apiKey\":\"abcdefghijklmnopqrstuvwxyz0123456789ABCDEF\"}}";
        var credentialDetector =
                new SlmSecretDetector(
                        model(
                                true,
                                Optional.of(
                                        "[\"job\",\"kind\",\"publish\",\"apiKey\",\"abcdefghijklmnopqrstuvwxyz0123456789ABCDEF\"]")));
        String masked = credentialDetector.mask(credential);
        assertFalse(masked.contains("abcdefghijklmnopqrstuvwxyz0123456789ABCDEF"));
        assertTrue(masked.contains("REDACTED"));
    }

    @Test
    void singleUnknownSensitiveScalarInStructuredTextRemainsModelDetected() {
        String scalar = "novel-sensitive-value";
        String text = "{\"note\":\"" + scalar + "\"}";
        var detector = new SlmSecretDetector(model(true, Optional.of("[\"" + scalar + "\"]")));
        assertEquals("{\"note\":\"[REDACTED_SLM_DETECTED]\"}", detector.mask(text));
    }

    private static @NonNull String quote(@NonNull String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void substringsMissingFromTheTextAreIgnoredAndFallBack() {
        var detector = new SlmSecretDetector(model(true, Optional.of("[\"not-in-the-text\"]")));
        String text = "no secrets here";
        assertTrue(detector.detect(text).isEmpty());
        assertEquals(text, detector.mask(text));
    }

    @Test
    void shortModelSubstringsAreIgnoredAndFallBack() {
        var detector = new SlmSecretDetector(model(true, Optional.of("[\"abc\"]")));
        String text = "abc is short";
        assertEquals(text, detector.mask(text));
    }

    @Test
    void garbageModelOutputFallsBackToDeterministicDetection() {
        var detector = new SlmSecretDetector(model(true, Optional.of("not json at all")));
        String masked = detector.mask("the password=hunter2x is set");
        assertTrue(masked.contains("[REDACTED_PASSWORD]"), masked);
        assertFalse(masked.contains("hunter2x"));
    }

    @Test
    void objectWrappedArrayIsAcceptedAsModelOutput() {
        var detector =
                new SlmSecretDetector(model(true, Optional.of("{\"secrets\":[\"wEk7-qR9\"]}")));
        assertEquals("credential: [REDACTED_SLM_DETECTED]", detector.mask("credential: wEk7-qR9"));
    }

    @Test
    void nonStringArrayElementsFallBackToDeterministicDetection() {
        var detector = new SlmSecretDetector(model(true, Optional.of("[123, 456]")));
        String masked = detector.mask("the password=hunter2x is set");
        assertTrue(masked.contains("[REDACTED_PASSWORD]"), masked);
    }

    @Test
    void unavailableOrEmptyModelFallsBackToDeterministicDetection() {
        String text = "the password=hunter2x is set";
        for (var detector :
                new SlmSecretDetector[] {
                    new SlmSecretDetector(null),
                    new SlmSecretDetector(model(false, Optional.of("[\"hunter2x\"]"))),
                    new SlmSecretDetector(model(true, Optional.empty()))
                }) {
            String masked = detector.mask(text);
            assertTrue(masked.contains("[REDACTED_PASSWORD]"), masked);
            assertFalse(masked.contains("hunter2x"));
        }
    }

    @Test
    void modelSecretsSurviveTruncatedPromptsByVerifyingAgainstTheFullText() {
        var detector = new SlmSecretDetector(model(true, Optional.of("[\"synthetic-token\"]")));
        String text = "x".repeat(3000) + " synthetic-token";
        assertTrue(detector.mask(text).endsWith(" [REDACTED_SLM_DETECTED]"));
    }
}
