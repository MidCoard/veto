package top.focess.veto.api.plugin.contract;

/** Cooperative cancellation signal only. This object carries no invocation authority. */
@FunctionalInterface
public interface Cancellation {
    /**
     * Reports whether cooperative cancellation has been requested.
     *
     * @return whether the host has requested cooperative cancellation
     */
    boolean isCancelled();

    /**
     * Throws a sanitized cancellation failure when cancellation has been requested.
     *
     * @throws PluginFailure with {@link PluginFailure.Code#CANCELLED} when cancelled
     */
    default void checkCancelled() throws PluginFailure {
        if (isCancelled()) throw new PluginFailure(PluginFailure.Code.CANCELLED);
    }
}
