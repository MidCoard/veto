package top.focess.veto.agent.capability;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** A call-scoped file handle; host paths and unrestricted file objects are never exposed. */
public sealed interface WorkspaceFile permits RestrictedWorkspaceFile, WritableWorkspaceFile {
    @NonNull String name();

    /** Returns file, directory, symbolic_link, or unavailable for a protected directory entry. */
    @NonNull String kind() throws IOException;

    long size() throws IOException;

    @NonNull InputStream openRead() throws IOException;

    @NonNull List<? extends @NonNull WorkspaceFile> children() throws IOException;
}
