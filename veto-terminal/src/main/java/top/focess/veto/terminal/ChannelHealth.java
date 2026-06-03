package top.focess.veto.terminal;

import top.focess.veto.contract.TerminalRequest;

/**
 * Quick liveness check on the file channel — sends a minimal request and verifies the backend
 * responds within a short timeout.
 */
public final class ChannelHealth {

    private ChannelHealth() {
    }

    /**
     * Returns true if the backend responds to a status ping within 5 seconds.
     */
    public static boolean check(FileChannel channel) {
        var resp = channel.send(new TerminalRequest("status"), 5_000);
        return resp != null;
    }
}
