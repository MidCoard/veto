package top.focess.veto.contract;

import org.jspecify.annotations.NonNull;

/**
 * Transport-agnostic seam for exchanging {@link Frame}s between a terminal (client) and the backend
 * (server).
 *
 * <p>The protocol module provides ZeroMQ and WebSocket implementations of these interfaces.
 * Applications own authentication, authorization and routing. Terminal interaction and rendering
 * remain outside this module.
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
    record FramedMsg(@NonNull String identity, @NonNull Frame frame) {}

    /**
     * Receives the next framed message.
     *
     * @param timeoutMillis timeout in milliseconds ({@code 0} non-blocking, {@code <0} infinite)
     * @return the next message, or {@code null} if none arrived within the timeout or a malformed
     *     payload was dropped
     */
    FramedMsg recv(long timeoutMillis);

    /** Closes the transport, releasing the underlying socket. */
    void close();
}
