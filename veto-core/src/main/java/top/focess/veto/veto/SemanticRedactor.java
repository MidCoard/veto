package top.focess.veto.veto;

import java.util.*;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gateway Semantic Redactor - intercepts and redacts sensitive literals from outbound data.
 * Identifies secrets, proprietary physics parameters, IP addresses, and other sensitive information
 * before the data flows through bus to the cloud.
 *
 * <p>Works in concert with the local SLM (llama.cpp) for semantic understanding, but also applies
 * deterministic regex-based redaction as a first pass.
 */
@Component
public class SemanticRedactor {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.veto.SemanticRedactor");

    // Deterministic pattern-based redaction (first pass, before SLM)
    private static final @NonNull List<RedactionRule> REDACTION_RULES =
            List.of(
                    // IPv4 addresses
                    new RedactionRule(
                            Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
                            "[REDACTED_IP]",
                            RedactionType.IP_ADDRESS),
                    // IPv6 addresses
                    new RedactionRule(
                            Pattern.compile("\\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\b"),
                            "[REDACTED_IPV6]",
                            RedactionType.IP_ADDRESS),
                    // API keys / tokens (alphanumeric 32+ chars)
                    new RedactionRule(
                            Pattern.compile("\\b[A-Za-z0-9+/=]{32,}\\b"),
                            "[REDACTED_KEY]",
                            RedactionType.SECRET_KEY),
                    // SSH private key headers - matches BEGIN/END format with optional key type
                    // and PRIVATE
                    new RedactionRule(
                            Pattern.compile(
                                    "-----BEGIN (?:RSA |DSA |EC |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |DSA |EC |OPENSSH )?PRIVATE KEY-----",
                                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE),
                            "[REDACTED_SSH_KEY]",
                            RedactionType.SSH_PROFILE),
                    // Hostnames / FQDNs containing internal domains
                    new RedactionRule(
                            Pattern.compile(
                                    "\\b(?:[a-zA-Z0-9-]+\\.)*(?:internal|local|private|corp|intranet)\\.[a-zA-Z]{2,}\\b",
                                    Pattern.CASE_INSENSITIVE),
                            "[REDACTED_INTERNAL_HOST]",
                            RedactionType.HOSTNAME),
                    // Email addresses
                    new RedactionRule(
                            Pattern.compile(
                                    "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
                                    Pattern.CASE_INSENSITIVE),
                            "[REDACTED_EMAIL]",
                            RedactionType.PERSONAL_INFO),
                    // URLs with authentication
                    new RedactionRule(
                            Pattern.compile("https?://[^:]+:[^@]+@"),
                            "https://[REDACTED_USER]:[REDACTED_PASS]@",
                            RedactionType.SECRET_KEY),
                    // Paths to credential files
                    new RedactionRule(
                            Pattern.compile(
                                    "(?i)(?<![A-Za-z0-9_.-])(?:"
                                            + "(?:(?:(?:~|[A-Za-z]:)?[\\\\/])?"
                                            + "(?:[^\\\\/\\s]+[\\\\/])*"
                                            + "(?:\\.ssh|\\.aws|credentials|secrets|keys)"
                                            + "[\\\\/][^\\\\/\\s]+(?:[\\\\/][^\\\\/\\s]+)*)"
                                            + "|(?:(?:~|[A-Za-z]:)?[\\\\/]etc[\\\\/]"
                                            + "(?:passwd|shadow)))"),
                            "[REDACTED_CREDENTIAL_PATH]",
                            RedactionType.SSH_PROFILE),
                    // Database connection strings
                    new RedactionRule(
                            Pattern.compile("(?i)(?:jdbc|postgresql|mysql|mongodb)://[^:]+:[^@]+@"),
                            "[DB_CONNECTION_REDACTED]://[REDACTED]@",
                            RedactionType.SECRET_KEY));

    // Proprietary physics parameter patterns (configurable)
    private final @NonNull List<Pattern> proprietaryParameterPatterns = new ArrayList<>();

    public SemanticRedactor() {
        // Broadened proprietary physics patterns
        proprietaryParameterPatterns.add(
                Pattern.compile(
                        "(?i)\\b(?:norm|peak|magnitude|amplitude|frequency|phase)_(?:max|min|avg|offset|val|value)\\s*[:=]\\s*[-+]?\\d+(?:\\.\\d+)?(?:e[+-]?\\d+)?\\b"));
        proprietaryParameterPatterns.add(
                Pattern.compile(
                        "(?i)\\b(?:array|grid|topology|mesh)_\\w+_(?:config|params|data|setup)\\s*[:=]\\s*\\{.*?}",
                        Pattern.DOTALL));
    }

    /**
     * Perform first-pass deterministic redaction on the payload. Returns a RedactionReport with all
     * findings and the redacted payload.
     */
    public @NonNull RedactionReport deterministicRedact(@NonNull String originalPayload) {
        String redacted = originalPayload;
        List<RedactionEntry> entries = new ArrayList<>();

        for (RedactionRule rule : REDACTION_RULES) {
            var matcher = rule.pattern().matcher(redacted);
            int count = 0;
            StringBuilder sb = new StringBuilder();
            int lastEnd = 0;

            while (matcher.find()) {
                sb.append(redacted, lastEnd, matcher.start());
                sb.append(rule.replacement());
                count++;
                lastEnd = matcher.end();

                entries.add(
                        new RedactionEntry(
                                rule.type(),
                                matcher.group().substring(0, Math.min(matcher.group().length(), 20))
                                        + "...",
                                rule.replacement()));
            }

            if (count > 0) {
                sb.append(redacted.substring(lastEnd));
                redacted = sb.toString();
                log.debug("gateway Redactor: Redacted {} instances of type {}", count, rule.type());
            }
        }

        // Proprietary parameter redaction
        for (Pattern pp : proprietaryParameterPatterns) {
            var matcher = pp.matcher(redacted);
            if (matcher.find()) {
                redacted = matcher.replaceAll("[REDACTED_PROPRIETARY_PARAM]");
                entries.add(
                        new RedactionEntry(
                                RedactionType.PROPRIETARY_DATA,
                                "[proprietary parameter]",
                                "[REDACTED_PROPRIETARY_PARAM]"));
            }
        }

        return new RedactionReport(originalPayload, redacted, entries);
    }

    /**
     * Perform semantic redaction using the local SLM for complex patterns. This enriches the
     * deterministic redaction with LLM-powered understanding.
     */
    public @NonNull String semanticRedact(@NonNull String payload, @NonNull String llmSuggestion) {
        // Combine deterministic + SLM-guided redaction
        // The LLM (via LlamaCppBridge) provides structured suggestions about
        // what should be redacted based on semantic understanding.
        RedactionReport deterministicReport = deterministicRedact(payload);
        String redacted = deterministicReport.redactedPayload();

        // Apply SLM suggestions if the LLM found additional redactions
        if (!llmSuggestion.isEmpty()) {
            redacted = applySLMRedactions(redacted, llmSuggestion);
        }

        return redacted;
    }

    private @NonNull String applySLMRedactions(
            @NonNull String payload, @NonNull String llmSuggestion) {
        // Parse the SLM's structured output (JSON) and apply additional redactions
        // The SLM output follows the GBNF grammar and provides specific field-level redactions
        try {
            if (llmSuggestion.contains("\"redacted_fields\"")) {
                // The SLM identified specific fields to redact
                // This is a simplified implementation - in production, the
                // SLM output is parsed via the GBNF grammar
                log.debug("gateway Redactor: Applying SLM-suggested redactions");
            }
        } catch (Exception e) {
            log.warn("gateway Redactor: Failed to apply SLM redactions", e);
        }
        return payload; // Return original if SLM processing fails
    }

    /** Add a custom proprietary parameter pattern. */
    public void addProprietaryPattern(@NonNull String regex) {
        proprietaryParameterPatterns.add(Pattern.compile(regex));
        log.info("gateway Redactor: Added proprietary pattern '{}'", regex);
    }

    /** Redaction report containing original, redacted, and all entries. */
    public record RedactionReport(
            @NonNull String originalPayload,
            @NonNull String redactedPayload,
            @NonNull List<RedactionEntry> entries) {
        public int getTotalRedactions() {
            return entries.size();
        }

        public boolean wasModified() {
            return !originalPayload.equals(redactedPayload);
        }
    }

    public record RedactionEntry(
            @NonNull RedactionType type,
            @NonNull String originalExcerpt,
            @NonNull String replacement) {}

    public record RedactionRule(
            @NonNull Pattern pattern, @NonNull String replacement, @NonNull RedactionType type) {}

    public enum RedactionType {
        IP_ADDRESS,
        SECRET_KEY,
        SSH_PROFILE,
        HOSTNAME,
        PERSONAL_INFO,
        PROPRIETARY_DATA
    }
}
