package top.focess.veto.plugin.contract;

import org.jspecify.annotations.NonNull;

/**
 * Typed handler for a record-authored tool. Only invoked by a host adapter after the arguments have
 * been deserialized into {@code A} and the call has been authorized; the returned {@code R} is
 * serialized back to JSON by the host.
 */
@FunctionalInterface
public interface RecordToolHandler<A, R> {
    @NonNull R invoke(@NonNull A args, @NonNull Cancellation cancellation) throws PluginFailure;
}
