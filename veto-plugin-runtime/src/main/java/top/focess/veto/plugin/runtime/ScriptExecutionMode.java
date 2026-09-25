package top.focess.veto.plugin.runtime;

import java.io.IOException;

/** Operator-selected script authority. An unavailable isolated mode never falls back to trust. */
public enum ScriptExecutionMode {
    TRUSTED,
    ISOLATED;

    /** Fails unless this mode can actually run scripts, honoring the trusted-code flag. */
    public void requireAvailable(boolean trustedCode) throws IOException {
        if (this == ISOLATED) {
            throw new IOException(
                    "Isolated script plugins are unavailable: no verified strict OS worker launcher "
                            + "enforces private filesystem access, denied subprocess creation, denied "
                            + "network access and resource limits. Trusted execution was not used.");
        }
        if (!trustedCode) {
            throw new IOException(
                    "Trusted script plugins require veto.plugins.trusted-code=true; "
                            + "scripts run as the server user.");
        }
    }
}
