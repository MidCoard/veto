package top.focess.veto.agent.mcp;

import org.jspecify.annotations.NonNull;

/** Typed, LLM-facing documentation sections for one tool. */
public record ToolDocumentation(
        @NonNull String behavior,
        @NonNull String whenToUse,
        @NonNull String whenNotToUse,
        @NonNull String resultContract,
        @NonNull String errorsAndEdgeCases,
        @NonNull String security) {

    private static final @NonNull ToolDocumentation EMPTY =
            new ToolDocumentation("", "", "", "", "", "");

    public ToolDocumentation {
        behavior = behavior.strip();
        whenToUse = whenToUse.strip();
        whenNotToUse = whenNotToUse.strip();
        resultContract = resultContract.strip();
        errorsAndEdgeCases = errorsAndEdgeCases.strip();
        security = security.strip();
    }

    public static @NonNull ToolDocumentation empty() {
        return EMPTY;
    }
}
