package top.focess.veto.llm.core;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/** Controls only the tool-result payload presented to the model. */
public enum ToolResultPresentationMode {
    BASIC,
    DETAILED;

    public boolean detailed() {
        return this == DETAILED;
    }

    public @NonNull ToolResultPresentationMode canonical() {
        return canonicalize(this);
    }

    public static @NonNull ToolResultPresentationMode canonicalize(
            ToolResultPresentationMode mode) {
        return mode != null && mode.detailed()
                ? Objects.requireNonNull(DETAILED)
                : Objects.requireNonNull(BASIC);
    }
}
