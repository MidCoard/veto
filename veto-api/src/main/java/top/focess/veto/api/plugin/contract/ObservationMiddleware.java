package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/**
 * Ordinary observation transformation. The host supplies initially protected text and must run
 * required final protection after all transforms, before publication. Implementations cannot
 * execute a tool or call the downstream pipeline themselves.
 */
public abstract class ObservationMiddleware {
    /** Constructs an observation aspect. */
    protected ObservationMiddleware() {}

    /**
     * Transforms one protected observation.
     *
     * @param observation protected observation text
     * @param cancellation cooperative cancellation signal
     * @return transformed observation text
     * @throws PluginFailure when transformation cannot complete
     */
    public abstract @NonNull String transform(
            @NonNull String observation, @NonNull Cancellation cancellation) throws PluginFailure;
}
