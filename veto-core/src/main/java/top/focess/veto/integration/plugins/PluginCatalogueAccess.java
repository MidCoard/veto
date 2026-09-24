package top.focess.veto.integration.plugins;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ReadOnlyCatalogueTree;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.resources.CatalogueAccess;
import top.focess.veto.api.resources.CatalogueTree;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Explicit operator roots and a live permit's workspace, bound to one plugin activation. */
final class PluginCatalogueAccess implements CatalogueAccess {
    private final @NonNull ManagedPlugin plugin;
    private final @NonNull PluginStorage storage;
    private final @NonNull Map<@NonNull String, @NonNull String> roots;

    PluginCatalogueAccess(
            @NonNull ManagedPlugin plugin,
            @NonNull PluginStorage storage,
            @NonNull Map<@NonNull String, @NonNull String> roots) {
        this.plugin = plugin;
        this.storage = storage;
        this.roots = Map.copyOf(roots);
    }

    private void active() {
        if (plugin.state() != PluginState.ACTIVE)
            throw new SecurityException("Catalogue plugin is not active");
    }

    public @NonNull Optional<CatalogueTree> shared(@NonNull String name) {
        active();
        String path = roots.get(name);
        return path == null
                ? Optional.empty()
                : Optional.of(new ReadOnlyCatalogueTree(List.of(Path.of(path)), this::active));
    }

    public @NonNull CatalogueTree workspace() {
        active();
        var context = ToolCallContextHolder.get();
        if (context == null
                || !plugin.bindingId().equals(context.executionPermit().remoteServerName()))
            throw new SecurityException("Catalogue requires this plugin's invocation");
        var scope = storage.currentSession();
        var permit = context.executionPermit();
        return new ReadOnlyCatalogueTree(
                permit.workspaceRoots(),
                () -> {
                    active();
                    var current = ToolCallContextHolder.get();
                    if (current == null
                            || current.executionPermit() != permit
                            || !storage.currentSession().equals(scope))
                        throw new SecurityException("Catalogue invocation expired");
                });
    }
}
