package top.focess.veto.api.llm;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/** Controls only the tool-result payload presented to the model. */
public enum ToolResultPresentationMode {
    /** Basic model-facing tool result. */
    BASIC,
    /** Expanded model-facing tool result. */
    DETAILED;

    /**
     * Returns whether this mode requests expanded output.
     *
     * @return true for {@link #DETAILED}
     */
    public boolean detailed() {
        return this == DETAILED;
    }

    /**
     * Returns the canonical non-null form of this mode.
     *
     * @return this mode
     */
    public @NonNull ToolResultPresentationMode canonical() {
        return canonicalize(this);
    }

    /**
     * Converts a nullable mode to a supported mode.
     *
     * @param mode requested mode, or {@code null} for basic
     * @return {@link #BASIC} or {@link #DETAILED}
     */
    public static @NonNull ToolResultPresentationMode canonicalize(
            ToolResultPresentationMode mode) {
        return mode != null && mode.detailed()
                ? Objects.requireNonNull(DETAILED)
                : Objects.requireNonNull(BASIC);
    }
}
