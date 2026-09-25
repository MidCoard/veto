package top.focess.veto.api.agent.capability;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** A call-scoped file handle; host paths and unrestricted file objects are never exposed. */
public interface WorkspaceFile {
    /**
     * Provides the name exposed within the authorized workspace view.
     *
     * @return the final path component exposed by this handle
     */
    @NonNull String name();

    /**
     * Identifies the entry kind.
     *
     * @return {@code file}, {@code directory}, {@code symbolic_link}, or {@code unavailable}
     * @throws IOException when metadata cannot be read
     */
    @NonNull String kind() throws IOException;

    /**
     * Reads the entry size without exposing an unrestricted filesystem path.
     *
     * @return the file size in bytes
     * @throws IOException when metadata cannot be read
     */
    long size() throws IOException;

    /**
     * Opens this authorized file for streaming reads.
     *
     * @return a stream that reads this authorized file
     * @throws IOException when this entry cannot be opened for reading
     */
    @NonNull InputStream openRead() throws IOException;

    /**
     * Enumerates only the immediate entries visible through this handle.
     *
     * @return authorized handles for the immediate children
     * @throws IOException when this entry is not a readable directory
     */
    @NonNull List<? extends @NonNull WorkspaceFile> children() throws IOException;
}
