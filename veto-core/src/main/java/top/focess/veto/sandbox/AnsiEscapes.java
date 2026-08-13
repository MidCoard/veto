package top.focess.veto.sandbox;

import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * Removes ANSI/VT escape sequences (SGR colors, cursor movement, OSC titles/hyperlinks) from
 * subprocess output. Color and style codes are TTY display directives; Veto pipes every subprocess,
 * so the directives have nowhere to render and the three consumers of the output — the agent's
 * context, the persisted history, and the UI ledger — all want the plain text. Escapes in the
 * context also cost tokens (a colored banner can be half escape bytes) and can derail fragile
 * models, so the scrub keeps the model-facing text clean.
 *
 * <p>Stripping happens once, at the substrate's decode seam ({@code waitCapped} for synchronous
 * runs, the drain loop for background tasks), so every downstream consumer sees the identical
 * cleaned string. Compliant CLIs are additionally told to keep color off at the source via {@code
 * NO_COLOR}/{@code FORCE_COLOR=0} in the child environment; this scrub catches the non-compliant
 * ones.
 */
public final class AnsiEscapes {

    /**
     * ANSI/VT escape sequences, matched in dependency order: OSC bodies first (their {@code ESC ]}
     * opener would otherwise be eaten by the two-byte Fe rule), then CSI (incl. the 8-bit form),
     * charset designators, then remaining two-byte Fe escapes.
     */
    private static final Pattern ESCAPE_PATTERN =
            Pattern.compile(
                    "\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)"
                            + "|\\u001B\\[[0-?]*[ -/]*[@-~]"
                            + "|\\u009B[0-?]*[ -/]*[@-~]"
                            + "|\\u001B[()][0-9A-Za-z]"
                            + "|\\u001B[@-Z\\\\^_]");

    private AnsiEscapes() {}

    /** Returns {@code text} with all recognized ANSI/VT escape sequences removed. */
    public static @NonNull String strip(@NonNull String text) {
        return ESCAPE_PATTERN.matcher(text).replaceAll("");
    }
}
