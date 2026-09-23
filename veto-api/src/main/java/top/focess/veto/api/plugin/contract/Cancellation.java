package top.focess.veto.api.plugin.contract;

/** Cooperative cancellation signal only. This object carries no invocation authority. */
@FunctionalInterface
public interface Cancellation {
    boolean isCancelled();

    default void checkCancelled() throws PluginFailure {
        if (isCancelled()) throw new PluginFailure(PluginFailure.Code.CANCELLED);
    }
}
