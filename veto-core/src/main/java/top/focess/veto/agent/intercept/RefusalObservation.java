package top.focess.veto.agent.intercept;

import org.jspecify.annotations.NonNull;

/**
 * Single owner of the refusal-observation grammar ({@code REFUSED - <detail>. The call was not
 * executed.}). Refusals are harness-synthesized observations - they are sentence-form by design (a
 * refusal is a decision the model must react to, and natural language steers that reaction better
 * than a status code), and they travel the same tool-response channel as tool CONTENT. That makes
 * the {@link #PREFIX} a <b>reserved prefix</b>: no tool result may ever begin with it, or the model
 * could mistake raw output for a veto decision.
 *
 * <p>The reservation is enforced, not just documented: every executed tool result funnels through
 * {@link IngressDefense#maskAndFrame}, which passes the body through {@link #neutralize(String)} -
 * a body that opens with the reserved prefix gets its leading {@code REFUSED} quoted ({@code
 * "REFUSED" - ...}), breaking the exact grammar while preserving the content for the model and the
 * audit reader.
 */
public final class RefusalObservation {

    /**
     * The reserved observation prefix. Only {@link #of(String)} may produce a body that starts with
     * it; tool output that does is rewritten by {@link #neutralize(String)}.
     */
    public static final @NonNull String PREFIX = "REFUSED - ";

    private RefusalObservation() {}

    /**
     * The observation body for a refused call. A bare "REFUSED" tells the model (and the audit
     * reader) nothing - the detail names WHO refused (the user, with their chosen option, or the
     * security policy) so a later turn can tell a user-decline apart from a policy-refusal.
     */
    public static @NonNull String of(@NonNull String detail) {
        return PREFIX + detail + ". The call was not executed.";
    }

    /**
     * Enforcement of the reserved-prefix rule for tool output: when {@code body} (after leading
     * whitespace) opens with {@link #PREFIX}, the leading {@code REFUSED} is quoted so the body no
     * longer matches the refusal grammar. Any other body passes through untouched.
     */
    public static @NonNull String neutralize(@NonNull String body) {
        int i = 0;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        if (!body.startsWith(PREFIX, i)) {
            return body;
        }
        return body.substring(0, i) + "\"REFUSED\" - " + body.substring(i + PREFIX.length());
    }
}
