package top.focess.veto.integration.plugins;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.TextEmbedding;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Host-only binding factory, removed before services are delivered to a plugin. */
@FunctionalInterface
public interface PluginEmbeddingFactory {
    @NonNull Optional<TextEmbedding> bind(@NonNull ManagedPlugin plugin);
}
