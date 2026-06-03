package top.focess.veto.veto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * C7 GBNF Grammar Engine - manages GBNF (GGML BNF) grammars for constrained decoding. GBNF grammars
 * define the structural constraints that the local SLM must adhere to, ensuring that redacted
 * output follows strict schemas.
 */
@Component
public class GBNFGrammarEngine {

    private static final Logger log = LoggerFactory.getLogger(GBNFGrammarEngine.class);

    private final VetoGatewayConfiguration config;
    private final ConcurrentHashMap<String, String> grammarCache = new ConcurrentHashMap<>();

    public GBNFGrammarEngine(VetoGatewayConfiguration config) {
        this.config = config;
    }

    /**
     * Load the main veto output grammar from the configured path.
     */
    public String loadVetoOutputGrammar() {
        return loadGrammar(config.getLlamaCpp().getGbnfGrammarPath());
    }

    /** Load a grammar from file, with caching. */
    public String loadGrammar(String grammarPath) {
        return grammarCache.computeIfAbsent(
                grammarPath,
                path -> {
                    try {
                        Path resolved = Path.of(path);
                        if (!Files.exists(resolved)) {
                            log.warn(
                                    "C7 GBNF: Grammar file not found at '{}', using default", path);
                            return getDefaultVetoGrammar();
                        }
                        String grammar = Files.readString(resolved);
                        log.info(
                                "C7 GBNF: Loaded grammar from '{}' ({} bytes)",
                                path,
                                grammar.length());
                        return grammar;
                    } catch (IOException e) {
                        log.error("C7 GBNF: Failed to load grammar from '{}'", path, e);
                        return getDefaultVetoGrammar();
                    }
                });
    }

    /**
     * Get the default veto output grammar. Enforces structured JSON output with redaction markers.
     */
    public String getDefaultVetoGrammar() {
        return """
root ::= veto-response
veto-response ::= "{" ws "veto_decision" ws ":" ws decision ws "," ws "data" ws ":" ws data-block ws "}"
decision ::= "\"pass\"" | "\"redact\"" | "\"block\""
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
    public String getCodeConstraintGrammar() {
        return """
root ::= code-constraint-response
code-constraint-response ::= "{" ws "valid" ws ":" ws boolean ws "," ws "violations" ws ":" ws violations-list ws "," ws "redacted" ws ":" ws redacted-block ws "}"
boolean ::= "true" | "false"
violations-list ::= "[" ws (violation ("," ws violation)*)? ws "]"
violation ::= "{" ws "type" ws ":" ws string ws "," ws "field" ws ":" ws string ws "," ws "severity" ws ":" ws severity ws "}"
severity ::= "\"low\"" | "\"medium\"" | "\"high\"" | "\"critical\""
redacted-block ::= "{" ws (redacted-field ("," ws redacted-field)*)? ws "}"
redacted-field ::= string ":" ws string
string ::= "\\"" [^"]* "\\""
                ws ::= [ \\t\\n]*
                """;
    }

    /** Get the secrets redaction grammar. */
    public String getSecretsRedactionGrammar() {
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

    /** Register a custom grammar. */
    public void registerGrammar(String name, String grammar) {
        grammarCache.put(name, grammar);
        log.info("C7 GBNF: Registered grammar '{}'", name);
    }
}
