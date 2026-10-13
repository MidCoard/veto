package top.focess.veto.contract;

import org.jspecify.annotations.NonNull;

/**
 * Client connection with no thread-safety guarantee for concurrent calls. Confine send, recv and
 * close to one owner thread, including while recv blocks; close is not a cross-thread interrupt.
 *
 * <p>An exclusive ownership transfer requires safe publication and no overlapping use, such as
 * thread start and a completed thread join. {@link ProtocolClient} provides a thread-safe facade
 * that creates, uses and closes its transport on one IO worker.
 */
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
