package top.focess.veto.api.agent.tool;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.resources.CatalogueTree;

/** Optional read-only tool availability and facts for its own MDC extension. */
public interface ToolPresentation {
    /**
     * Describes availability and prompt facts using a read-only catalogue snapshot.
     *
     * @param workspace read-only catalogue visible to the tool
     * @return availability and immutable prompt facts
     */
    @NonNull State describe(@NonNull CatalogueTree workspace);

    /**
     * Tool availability and facts used by its prompt extension.
     *
     * @param available whether the tool should be advertised
     * @param facts structured facts for prompt rendering
     */
    record State(boolean available, @NonNull Map<String, Object> facts) {
        /** Defensively copies the facts map. */
        public State {
            facts = Map.copyOf(facts);
        }
    }
}
