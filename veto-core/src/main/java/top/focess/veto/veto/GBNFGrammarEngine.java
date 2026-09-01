package top.focess.veto.veto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gateway GBNF Grammar Engine - manages GBNF (GGML BNF) grammars for constrained decoding. GBNF
 * grammars define the structural constraints that the local SLM must adhere to, ensuring that
 * redacted output follows strict schemas.
 */
@Component
public class GBNFGrammarEngine {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.veto.GBNFGrammarEngine");

    private final @NonNull VetoGatewayConfiguration config;
    private final @NonNull ConcurrentHashMap<String, String> grammarCache =
            new ConcurrentHashMap<>();

    public GBNFGrammarEngine(@NonNull VetoGatewayConfiguration config) {
        this.config = config;
    }

    /** Load the main veto output grammar from the configured path. */
    public @NonNull String loadVetoOutputGrammar() {
        return loadGrammar(config.getLlamaCpp().getGbnfGrammarPath());
    }

    /** Load a grammar from file, with caching. */
    public @NonNull String loadGrammar(@NonNull String grammarPath) {
        return grammarCache.computeIfAbsent(
                grammarPath,
                path -> {
                    try {
                        Path resolved = Path.of(path);
                        if (!Files.exists(resolved)) {
                            log.warn(
                                    "gateway GBNF: Grammar file not found at '{}', using default",
                                    path);
                            return getDefaultVetoGrammar();
                        }
                        String grammar = Files.readString(resolved);
                        log.info(
                                "gateway GBNF: Loaded grammar from '{}' ({} bytes)",
                                path,
                                grammar.length());
                        return grammar;
                    } catch (IOException e) {
                        log.error("gateway GBNF: Failed to load grammar from '{}'", path, e);
                        return getDefaultVetoGrammar();
                    }
                });
    }

    /**
     * Get the default veto output grammar. Enforces structured JSON output with redaction markers.
     */
    public @NonNull String getDefaultVetoGrammar() {
        return """
                root ::= veto-response
                veto-response ::= "{" ws "veto_decision" ws ":" ws decision ws "," ws "data" ws ":" ws data-block ws "}"
                decision ::= ""pass"" | ""redact"" | ""block""
                data-block ::= "{" ws data-fields ws "}"
                data-fields ::= data-field ("," ws data-field)*
                data-field ::= string ":" ws (string | number | "null")
                string ::= "\\"" [^"]* "\\""
                number ::= [0-9]+ ("." [0-9]+)?
                ws ::= [ \\t\\n]*
                """;
    }

    /**
     * Get the structural constraints grammar for code validation. Enforces that code output adheres
     * to project rules (e.g., normalized physics values).
     */
    public @NonNull String getCodeConstraintGrammar() {
        return """
                root ::= code-constraint-response
                code-constraint-response ::= "{" ws "valid" ws ":" ws boolean ws "," ws "violations" ws ":" ws violations-list ws "," ws "redacted" ws ":" ws redacted-block ws "}"
                boolean ::= "true" | "false"
                violations-list ::= "[" ws (violation ("," ws violation)*)? ws "]"
                violation ::= "{" ws "type" ws ":" ws string ws "," ws "field" ws ":" ws string ws "," ws "severity" ws ":" ws severity ws "}"
                severity ::= ""low"" | ""medium"" | ""high"" | ""critical""
                redacted-block ::= "{" ws (redacted-field ("," ws redacted-field)*)? ws "}"
                redacted-field ::= string ":" ws string
                string ::= "\\"" [^"]* "\\""
                ws ::= [ \\t\\n]*
                """;
    }

    /** Get the secrets redaction grammar. */
    public @NonNull String getSecretsRedactionGrammar() {
        return """
                root ::= redaction-response
                redaction-response ::= "{" ws "secrets_found" ws ":" ws boolean ws "," ws "redacted_fields" ws ":" ws redacted-fields ws "," ws "safe_payload" ws ":" ws string ws "}"
                redacted-fields ::= "[" ws (redacted-field ("," ws redacted-field)*)? ws "]"
                redacted-field ::= "{" ws "field" ws ":" ws string ws "," ws "type" ws ":" ws string ws "}"
                boolean ::= "true" | "false"
                string ::= "\\"" [^"]* "\\""
                ws ::= [ \\t\\n]*
                """;
    }

    /** Grammar for the advisory relevance + danger screening contract. */
    public @NonNull String getScreeningGrammar() {
        return """
                root ::= "{" ws "\\"relevance\\"" ws ":" ws relevance "," ws "\\"danger\\"" ws ":" ws danger "," ws "\\"reason\\"" ws ":" ws string ws "}"
                relevance ::= "\\"HIGH\\"" | "\\"MEDIUM\\"" | "\\"LOW\\""
                danger ::= "\\"SAFE\\"" | "\\"ELEVATED\\"" | "\\"DANGEROUS\\"" | "\\"CRITICAL\\""
                string ::= "\\\"" ([^"\\\\] | "\\\\" (["\\\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]))* "\\\""
                ws ::= [ \\t\\n]*
                """;
    }

    /** Resolve a public grammar name to the actual GBNF text expected by llama.cpp. */
    public @NonNull String resolveGrammar(@NonNull String name) {
        String registered = grammarCache.get(name);
        if (registered != null) {
            return registered;
        }
        return switch (name) {
            case "veto-screening", "veto-relevance" -> getScreeningGrammar();
            case "veto-semantic-mask" -> getSecretsRedactionGrammar();
            case "veto-output" -> loadVetoOutputGrammar();
            default -> loadGrammar(name);
        };
    }

    /** Register a custom grammar. */
    public void registerGrammar(@NonNull String name, @NonNull String grammar) {
        grammarCache.put(name, grammar);
        log.info("gateway GBNF: Registered grammar '{}'", name);
    }
}
