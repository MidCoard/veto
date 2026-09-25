package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;

/**
 * Typed, LLM-facing documentation sections for one tool.
 *
 * @param behavior what the tool does after a valid call reaches its handler
 * @param whenToUse positive selection guidance
 * @param whenNotToUse negative selection guidance and alternatives
 * @param resultContract normative success and failure content shapes
 * @param errorsAndEdgeCases limits, recovery guidance, and edge conditions
 * @param security agent-facing access restrictions and obligations
 */
public record ToolDocumentation(
        @NonNull String behavior,
        @NonNull String whenToUse,
        @NonNull String whenNotToUse,
        @NonNull String resultContract,
        @NonNull String errorsAndEdgeCases,
        @NonNull String security) {

    private static final @NonNull ToolDocumentation EMPTY =
            new ToolDocumentation("", "", "", "", "", "");

    /** Normalizes every section by removing surrounding whitespace. */
    public ToolDocumentation {
        behavior = behavior.strip();
        whenToUse = whenToUse.strip();
        whenNotToUse = whenNotToUse.strip();
        resultContract = resultContract.strip();
        errorsAndEdgeCases = errorsAndEdgeCases.strip();
        security = security.strip();
    }

    /**
     * Provides an immutable empty value for undocumented tools.
     *
     * @return the shared documentation value with every section empty
     */
    public static @NonNull ToolDocumentation empty() {
        return EMPTY;
    }
}
