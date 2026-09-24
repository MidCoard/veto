package top.focess.veto.secret.detection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.llm.LocalModelCompletion;
import top.focess.veto.api.llm.PromptRenderer;

class MdcSecretDetectionModelTest {
    @Test
    void compilesMdcThenUsesThePluginsArrayGrammarAndExactBoundText() {
        var captured = new AtomicReference<LocalModelCompletion.Request>();
        LocalModelCompletion model =
                new LocalModelCompletion() {
                    public boolean isAvailable() {
                        return true;
                    }

                    public @NonNull Optional<String> complete(@NonNull Request request) {
                        captured.set(request);
                        return Optional.of("[\"synthetic-token\"]");
                    }
                };
        PromptRenderer renderer =
                (source, data) -> {
                    assertEquals("secret-detection", source);
                    assertEquals("value synthetic-token", data.get("text"));
                    return "compiled MDC content";
                };
        var detector = new SlmSecretDetector(new MdcSecretDetectionModel(model, renderer));
        assertEquals("value [REDACTED_SLM_DETECTED]", detector.mask("value synthetic-token"));
        var request = captured.get();
        if (request == null) throw new AssertionError("Missing model invocation");
        assertEquals("compiled MDC content", request.prompt());
        assertEquals("secret-detection", request.purpose());
        assertTrue(request.grammar().startsWith("root ::= \"[\""));
        assertFalse(request.grammar().contains("veto_decision"));
    }

    @Test
    void mdcFailureNeverFallsBackToAnInlinePromptOrStopsDeterministicMasking() {
        LocalModelCompletion model =
                new LocalModelCompletion() {
                    public boolean isAvailable() {
                        return true;
                    }

                    public @NonNull Optional<String> complete(@NonNull Request request) {
                        throw new AssertionError("Uncompiled prompt must not reach transport");
                    }
                };
        var detector =
                new SlmSecretDetector(
                        new MdcSecretDetectionModel(
                                model,
                                (source, data) -> {
                                    throw new IllegalStateException("Missing MDC");
                                }));
        assertFalse(detector.mask("password=synthetic-token").contains("synthetic-token"));
    }
}
