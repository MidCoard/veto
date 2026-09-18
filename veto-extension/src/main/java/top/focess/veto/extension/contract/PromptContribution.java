package top.focess.veto.extension.contract;

import org.jspecify.annotations.NonNull;

/** A static package resource. The host owns resolution, provenance and placement. */
public record PromptContribution(@NonNull String resource) {
    public PromptContribution {
        if (resource.length() > 256 || !resource.matches("prompts/[a-zA-Z0-9_-]+\\.md"))
            throw new IllegalArgumentException("Invalid prompt resource");
    }
}
