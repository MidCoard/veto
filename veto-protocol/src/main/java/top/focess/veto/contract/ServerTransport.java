package top.focess.veto.contract;

import org.jspecify.annotations.NonNull;

/** Server connection with peer-addressed output. All IO and close belong to one owner thread. */
public interface ServerTransport extends AutoCloseable {
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
