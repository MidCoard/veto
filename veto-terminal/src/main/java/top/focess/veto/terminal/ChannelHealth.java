package top.focess.veto.terminal;

import top.focess.veto.contract.IpcFrame;

/**
 * Quick liveness check on the IPC channel — sends a minimal request and verifies the backend
 * responds within a short timeout. A backend error response (e.g. "not logged in") still counts as
 * reachable; a connection-level error (e.g. "too many connections") is fatal.
 */
public final class ChannelHealth {

    private ChannelHealth() {}

    /**
     * Check backend reachability. Returns the raw response frame so the caller can distinguish
     * between "backend healthy", "backend reached but returned error", and "unreachable".
     */
    public static IpcFrame check(FileChannel channel) {
        try {
            return channel.sendAndReceive(
                    new IpcFrame.Request("/status"),
                    5_000,
                    new FileChannel.FrameHandler() {
                        @Override
                        public void onFrame(IpcFrame frame) {}

                        @Override
                        public IpcFrame.Input onPrompt(IpcFrame.Prompt prompt) {
                            return null;
                        }
                    });
        } catch (Exception e) {
            return null;
        }
    }

    /** True if any response came back from the backend — even an error — meaning it's alive. */
    public static boolean isReachable(IpcFrame resp) {
        return resp != null;
    }

    /**
     * True if the response indicates a fatal connection-level rejection (e.g. max connections
     * reached). The terminal should display the error and exit.
     */
    public static boolean isFatal(IpcFrame resp) {
        if (resp instanceof IpcFrame.Error err) {
            String msg = err.content();
            return msg != null
                    && (msg.contains("too many connections") || msg.contains("Server busy"));
        }
        return false;
    }
}
