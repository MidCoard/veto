package top.focess.veto.contract;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Transport-agnostic seam for exchanging {@link IpcFrame}s between a terminal (client) and the
 * backend (server).
 *
 * <p>A local desktop deployment uses {@link ZmqChannel}; remote/cloud deployments wrap the same
 * frames in WSS or gRPC tunnels. The terminal's connection layer ({@code IpcClient}) depends on
 * this interface, never on ZMQ, so the transport can be swapped without touching the terminal.
 *
 * <h3>Receive timeout convention</h3>
 *
 * {@link #recv(long)} takes a millisecond timeout where:
 *
 * <ul>
 *   <li>{@code 0} — non-blocking (return immediately if nothing is waiting);
 *   <li>{@code > 0} — block up to that many milliseconds;
 *   <li>{@code < 0} — block indefinitely until a frame arrives.
 * </ul>
 *
 * <p>A malformed payload is dropped by the transport (logged), not surfaced; {@code recv} returns
 * {@code null} for "no message" whether the cause was timeout, empty poll, or a dropped malformed
 * frame.
 *
 * <p>The two send shapes are split into {@link ClientTransport} and {@link ServerTransport} so a
 * caller cannot accidentally invoke the wrong one (e.g. sending a ROUTER identity frame on a
 * DEALER).
 */
public sealed interface Transport permits ClientTransport, ServerTransport {

    /** A received frame paired with its sender routing identity (empty for client-side DEALER). */
    record FramedMsg(@NonNull String identity, @NonNull IpcFrame frame) {}

    /**
     * Receives the next framed message.
     *
     * @param timeoutMillis timeout in milliseconds ({@code 0} non-blocking, {@code <0} infinite)
     * @return the next message, or {@code null} if none arrived within the timeout or a malformed
     *     payload was dropped
     */
    @Nullable FramedMsg recv(long timeoutMillis);

    /** Closes the transport, releasing the underlying socket. */
    void close();
}
