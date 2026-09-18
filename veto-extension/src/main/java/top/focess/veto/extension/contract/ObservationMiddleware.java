package top.focess.veto.extension.contract;

import org.jspecify.annotations.NonNull;

/**
 * Ordinary observation transformation. The host supplies initially protected text and must run
 * required final protection after all transforms, before publication. Implementations cannot
 * execute a tool or call the downstream pipeline themselves.
 */
@FunctionalInterface
public interface ObservationMiddleware {
    @NonNull String transform(@NonNull String observation, @NonNull Cancellation cancellation)
            throws ExtensionFailure;
}
