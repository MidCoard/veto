package top.focess.veto.contract;

import org.jspecify.annotations.NonNull;

/**
 * Transport half that sends frames from the backend (server) to a specific connected terminal —
 * e.g. a ZMQ ROUTER socket that addresses each peer by identity.
 *
 * <p>Split from {@link ClientTransport} so the two send shapes cannot be confused: a server
 * transport only offers {@link #send(String, IpcFrame)}, never the bare client send.
 */
public non-sealed interface ServerTransport extends Transport {

    /**
     * Sends a frame to a specific connected peer.
     *
     * @param identity the destination peer routing identity
     * @param frame the frame to send
     */
    void send(@NonNull String identity, @NonNull IpcFrame frame);
}
