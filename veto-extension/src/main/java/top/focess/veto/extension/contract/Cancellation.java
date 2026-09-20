package top.focess.veto.extension.contract;

/** Cooperative cancellation signal only. This object carries no invocation authority. */
@FunctionalInterface
public interface Cancellation {
    boolean isCancelled();

    default void checkCancelled() throws ExtensionFailure {
        if (isCancelled()) throw new ExtensionFailure(ExtensionFailure.Code.CANCELLED);
    }
}
