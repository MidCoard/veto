package top.focess.veto.agent.intercept;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * Best-effort secret-pattern scrubber. Replaces common secret tokens in tool observation text with
 * {@code [REDACTED_*]} markers before the observation enters the LLM's context (screening_model.md
 * §4.2, network_hitl_protocol.md §4.2).
 *
 * <p>Patterns are deliberately broad to err on the side of scrubbing; the authoritative secrets
 * control is the Vault (per_user_credential_isolation.md). This masker is the second line of
 * defense.
 *
 * <p>Stable for testing: every replacement is non-empty and the same input always produces the same
 * output.
 */
public final class SecretMasker {

    /**
     * The patterns + their replacement tag. Order matters — the first match wins per position. Keep
     * this list conservative; false positives (over-scrubbing) are preferred to false negatives
     * (leaking a secret).
     */
    private static final LinkedHashMap<Pattern, String> PATTERNS = buildPatterns();

    private static LinkedHashMap<Pattern, String> buildPatterns() {
        LinkedHashMap<Pattern, String> m = new LinkedHashMap<>();
        // AWS access key
        m.put(Pattern.compile("AKIA[0-9A-Z]{16}"), "[REDACTED_AWS_KEY]");
        // Generic API key shape (long alphanumeric)
        m.put(Pattern.compile("\\b[A-Za-z0-9_\\-]{32,}\\b"), "[REDACTED_API_KEY]");
        // Private key block
        m.put(
                Pattern.compile(
                        "-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----"
                                + "[\\s\\S]*?"
                                + "-----END (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----"),
                "[REDACTED_PRIVATE_KEY]");
        // DB connection string
        m.put(
                Pattern.compile(
                        "(?i)(jdbc:[a-z]+://[^\\s'\"]+|postgres(?:ql)?://[^\\s'\"]+|mysql://[^\\s'\"]+)"),
                "[REDACTED_DB_URL]");
        // password=... or pwd=...
        m.put(
                Pattern.compile("(?i)(password|passwd|pwd)\\s*[=:]\\s*[^\\s,'\"}]+"),
                "[REDACTED_PASSWORD]");
        // token=... (bearer / oauth)
        m.put(
                Pattern.compile("(?i)(token|bearer|api[_-]?key)\\s*[=:]\\s*[^\\s,'\"}]+"),
                "[REDACTED_TOKEN]");
        // GitHub personal access token (ghp_)
        m.put(Pattern.compile("\\bghp_[A-Za-z0-9]{30,}\\b"), "[REDACTED_GH_TOKEN]");
        // Slack tokens
        m.put(Pattern.compile("\\bxox[abpr]-[A-Za-z0-9-]{10,}\\b"), "[REDACTED_SLACK_TOKEN]");
        return m;
    }

    private SecretMasker() {}

    /**
     * Scrubs secret patterns in the input. Each pattern's first match is replaced with its {@code
     * [REDACTED_*]} tag. Stable for testing (no randomness).
     */
    public static @NonNull String mask(@NonNull String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = input;
        for (Map.Entry<Pattern, String> e : PATTERNS.entrySet()) {
            Matcher m = e.getKey().matcher(out);
            if (m.find()) {
                out = m.replaceAll(Matcher.quoteReplacement(e.getValue()));
            }
        }
        return out;
    }
}
