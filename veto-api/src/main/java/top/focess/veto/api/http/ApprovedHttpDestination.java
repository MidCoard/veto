package top.focess.veto.api.http;

import org.jspecify.annotations.NullMarked;
import top.focess.veto.api.plugin.agent.IsolatedAgent;

/** Authority for one screened URL argument. It cannot be retargeted. */
@NullMarked
public interface ApprovedHttpDestination extends AutoCloseable {
    void bind(IsolatedAgent.Runtime child, String operation);

    HttpDocument fetch();

    /**
     * Publishes only host-generated execution metadata after this child has settled successfully.
     */
    void publish(IsolatedAgent child);

    @Override
    void close();
}
