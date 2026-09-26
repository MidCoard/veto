package top.focess.veto.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.PluginFailure;

/**
 * Admission bridge between dispatch and the owning plugin's lifecycle. The registry calls this so a
 * handler body runs only while the contributing plugin permits invocation; the implementation is
 * the host, which delegates to the plugin's admission slot.
 */
public interface PluginExecutor {
    /** A handler body that may throw; run under the contributing plugin's admission. */
    @FunctionalInterface
    interface Body {
        /**
         * Runs the handler body.
         *
         * @throws Exception if the handler throws
         */
        void run() throws Exception;
    }

    /**
     * Runs the body under the named plugin's admission.
     *
     * @param namespace the contributing plugin identity
     * @param body the handler work to admit
     * @throws PluginFailure when the plugin is not admitting calls
     */
    void admit(@NonNull String namespace, @NonNull Body body) throws PluginFailure;
}
