package top.focess.veto.contract;

import java.io.IOException;
import org.jspecify.annotations.NonNull;

/**
 * A full-duplex connection carrying complete protocol frames.
 *
 * <p><b>Thread safety:</b> one reader and one writer may operate concurrently. Each direction must
 * have a single owner; callers must not overlap two sends or two receives. Close is idempotent, may
 * run on any thread and unblocks pending IO. Implementations bound blocking writes and release
 * partially opened resources on setup failure. Frame payloads must remain unchanged during send.
 */
public interface FrameTransport extends AutoCloseable {
    int MAX_FRAME_BYTES = 1024 * 1024;

    /** Writes one complete frame; completion is not acknowledgement of remote execution. */
    void send(@NonNull Frame frame) throws IOException;

    /** Blocks for a complete frame. EOF, malformed input and IO failures end the connection. */
    @NonNull Frame receive() throws IOException;

    @Override
    void close();
}
