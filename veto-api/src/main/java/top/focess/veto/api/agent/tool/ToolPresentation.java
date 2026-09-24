package top.focess.veto.api.agent.tool;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.resources.CatalogueTree;

/** Optional read-only tool availability and facts for its own MDC extension. */
public interface ToolPresentation {
    @NonNull State describe(@NonNull CatalogueTree workspace);

    record State(boolean available, @NonNull Map<String, Object> facts) {
        public State {
            facts = Map.copyOf(facts);
        }
    }
}
