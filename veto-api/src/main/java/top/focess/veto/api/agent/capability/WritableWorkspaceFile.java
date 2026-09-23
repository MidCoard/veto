package top.focess.veto.api.agent.capability;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** Basic mutations of a file authorized for the current invocation. */
public interface WritableWorkspaceFile extends WorkspaceFile {
    /** Publishes on close, refusing to replace an existing directory entry. */
    @NonNull OutputStream openForCreate() throws IOException;

    /** Publishes an atomic replacement on close. */
    @NonNull OutputStream openForReplace() throws IOException;

    void moveTo(@NonNull WritableWorkspaceFile target) throws IOException;

    /** Deletes this entry only; directory traversal is the caller's responsibility. */
    void delete() throws IOException;

    @Override
    @NonNull List<@NonNull WritableWorkspaceFile> children() throws IOException;
}
