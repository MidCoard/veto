package top.focess.veto.api.agent.capability;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** Basic mutations of a file authorized for the current invocation. */
public interface WritableWorkspaceFile extends WorkspaceFile {
    /**
     * Opens a new entry that is published on close and refuses replacement.
     *
     * @return the bounded output stream
     * @throws IOException when the target exists or cannot be created safely
     */
    @NonNull OutputStream openForCreate() throws IOException;

    /**
     * Opens content that atomically replaces this entry on close.
     *
     * @return the bounded output stream
     * @throws IOException when the entry cannot be replaced safely
     */
    @NonNull OutputStream openForReplace() throws IOException;

    /**
     * Moves this entry to another authorized workspace handle.
     *
     * @param target destination handle
     * @throws IOException when the move is unsafe or cannot be completed
     */
    void moveTo(@NonNull WritableWorkspaceFile target) throws IOException;

    /**
     * Deletes this entry only; directory traversal is the caller's responsibility.
     *
     * @throws IOException when the entry cannot be deleted safely
     */
    void delete() throws IOException;

    /**
     * @return authorized writable handles for the immediate children
     * @throws IOException when this entry is not a readable directory
     */
    @Override
    @NonNull List<@NonNull WritableWorkspaceFile> children() throws IOException;
}
