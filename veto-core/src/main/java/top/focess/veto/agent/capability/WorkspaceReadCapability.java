package top.focess.veto.agent.capability;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** Bounded read-only filesystem operations over paths authorized for one tool call. */
public sealed interface WorkspaceReadCapability extends Capability
        permits WorkspaceReadCapabilityImpl {

    /** Lists direct children of the path bound to {@code pathArgument}. */
    @NonNull String listDirectory(@NonNull String pathArgument);

    /** Reads a bounded, optionally ranged UTF-8 text file. */
    @NonNull String readText(@NonNull String pathArgument, Integer startLine, Integer endLine);

    /** Finds regular files below the path bound to {@code pathArgument}. */
    @NonNull String findFiles(@NonNull String pathArgument, @NonNull String pattern);

    /** Searches readable UTF-8 files below the path bound to {@code pathArgument}. */
    @NonNull String grep(
            @NonNull String pathArgument,
            @NonNull String query,
            boolean caseInsensitive,
            List<String> includes);
}
