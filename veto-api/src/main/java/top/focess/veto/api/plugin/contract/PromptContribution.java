package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/** Plugin-owned package prompt resource resolved by the host. */
public final class PromptContribution {
    private final @NonNull String resource;

    /**
     * Constructs a prompt aspect from a package-relative Markdown resource.
     *
     * @param resource package-relative path under {@code prompts/}
     * @throws IllegalArgumentException when the resource path is invalid
     */
    public PromptContribution(@NonNull String resource) {
        if (resource.length() > 256 || !resource.matches("prompts/[a-zA-Z0-9_-]+\\.md"))
            throw new IllegalArgumentException("Invalid prompt resource");
        this.resource = resource;
    }

    /**
     * Returns package-relative Markdown resource under {@code prompts/}.
     *
     * @return package-relative Markdown resource under {@code prompts/}
     */
    public @NonNull String resource() {
        return resource;
    }
}
