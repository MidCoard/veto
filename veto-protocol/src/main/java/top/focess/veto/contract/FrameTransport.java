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
    /** Writes one complete frame; completion is not acknowledgement of remote execution. */
    void send(@NonNull Frame frame) throws IOException;

    /**
     * Blocks for a complete frame. The transport owns receive-idle detection independently of
     * outbound traffic. After EOF, malformed input or IO failure, the owner must close the
     * connection. Failure does not imply that the transport has already released its resources.
     */
    @NonNull Frame receive() throws IOException;

    /**
     * Releases this transport's IO resources without joining protocol workers or invoking endpoint
     * callbacks. Implementations handle expected IO cleanup failures locally. The endpoint calls
     * close directly, then joins its workers after their IO has been unblocked.
     */
    @Override
    void close();
}
