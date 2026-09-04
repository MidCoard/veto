package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;

/** Gateway-issued authority for one screened workspace mutation call. */
public sealed interface WorkspaceWriteCapability extends Capability
        permits WorkspaceWriteCapabilityImpl {

    @NonNull String writeText(
            @NonNull String pathArgument, @NonNull String content, boolean overwrite);

    @NonNull String replaceText(
            @NonNull String pathArgument,
            int startLine,
            int endLine,
            @NonNull String targetContent,
            @NonNull String replacementContent);

    @NonNull String movePath(@NonNull String sourceArgument, @NonNull String destinationArgument);

    @NonNull String deletePath(@NonNull String pathArgument, boolean recursive);
}
