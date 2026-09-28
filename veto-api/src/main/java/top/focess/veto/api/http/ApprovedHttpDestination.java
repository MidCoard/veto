package top.focess.veto.api.http;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.agent.IsolatedAgent;

/** Authority for one screened URL argument. It cannot be retargeted. */
public interface ApprovedHttpDestination extends AutoCloseable {
    /**
     * Binds this destination to a child and its approved operation.
     *
     * @param child isolated child runtime
     * @param operation host-approved operation identifier
     */
    void bind(IsolatedAgent.@NonNull Runtime child, @NonNull String operation);

    /**
     * Fetches the screened destination under its current permit.
     *
     * @return bounded HTTP document
     */
    @NonNull HttpDocument fetch();

    /**
     * Publishes only host-generated execution metadata after this child has settled successfully.
     *
     * @param child successfully settled child
     */
    void publish(@NonNull IsolatedAgent child);

    /** Releases this destination grant. */
    @Override
    void close();
}
