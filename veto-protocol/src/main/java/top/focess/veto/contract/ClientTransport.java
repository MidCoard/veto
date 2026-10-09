package top.focess.veto.contract;

import org.jspecify.annotations.NonNull;

/** Client connection. All IO and close calls belong to one owner thread. */
public interface ClientTransport extends AutoCloseable {
    void send(Frame.@NonNull ClientFrame frame);

    /**
     * Zero polls, positive milliseconds wait, negative waits indefinitely. Malformed input is
     * skipped.
     */
    Frame.ServerFrame recv(long timeoutMillis);

    @Override
    void close();
}
