package top.focess.veto.contract;

import org.jetbrains.annotations.NotNull;

/**
 * Transport half that sends frames from the terminal (client) to the backend — e.g. a ZMQ DEALER
 * socket that automatically prepends its identity and sends a bare payload.
 *
 * <p>Split from {@link ServerTransport} so the two send shapes cannot be confused: a client
 * transport only offers {@link #send(IpcFrame.ClientFrame)}, never the identity-addressed server
 * send.
 */
public non-sealed interface ClientTransport extends Transport {

    /**
     * Sends a client→server frame.
     *
     * @param frame the frame to send
     */
    void send(@NotNull IpcFrame.ClientFrame frame);
}
