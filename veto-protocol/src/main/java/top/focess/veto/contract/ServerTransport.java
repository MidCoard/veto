package top.focess.veto.contract;

import org.jspecify.annotations.NonNull;

/**
 * Server connection with peer-addressed output and no thread-safety guarantee for concurrent calls.
 * Send, recv and close require one exclusive owner; close must not race a blocked recv.
 *
 * <p>Initialization may hand ownership to an IO worker via thread start, and cleanup may take it
 * back after a completed thread join. Such transfers require safe publication and no overlapping
 * access; context lifetime ownership alone does not provide synchronization.
 */
public interface ServerTransport extends AutoCloseable {
    /**
     * Immutable envelope; sharing the enclosed frame is subject to {@link Frame}'s payload rules.
     */
    record Message(@NonNull String identity, Frame.@NonNull ClientFrame frame) {}

    void send(@NonNull String identity, Frame.@NonNull ServerFrame frame);

    /**
     * Zero polls, positive milliseconds wait, negative waits indefinitely. Malformed input is
     * skipped.
     */
    Message recv(long timeoutMillis);

    @Override
    void close();
}
