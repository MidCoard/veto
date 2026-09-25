package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/**
 * A static package resource. The host owns resolution, provenance and placement.
 *
 * @param resource validated package-relative Markdown resource under {@code prompts/}
 */
public record PromptContribution(@NonNull String resource) {
    /** Validates the package-relative prompt resource path. */
    public PromptContribution {
        if (resource.length() > 256 || !resource.matches("prompts/[a-zA-Z0-9_-]+\\.md"))
            throw new IllegalArgumentException("Invalid prompt resource");
    }
}
