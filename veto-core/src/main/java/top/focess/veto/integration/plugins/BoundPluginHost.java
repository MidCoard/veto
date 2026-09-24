package top.focess.veto.integration.plugins;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Every background effect is restricted to sessions that selected this plugin instance. */
final class BoundPluginHost implements PluginHost {
    private final @NonNull PluginHost delegate;
    private final @NonNull Function<@NonNull String, @NonNull String> toolNames;
    private final @NonNull ManagedPlugin plugin;
    private final @NonNull PluginStorage storage;
    private final @NonNull PluginStorageFactory factory;
    private final @NonNull Map<String, PluginStorage.SessionScope> scopes =
            new ConcurrentHashMap<>();

    BoundPluginHost(
            @NonNull PluginHost delegate,
            @NonNull ManagedPlugin plugin,
            @NonNull PluginStorage storage,
            @NonNull PluginStorageFactory factory) {
        this(delegate, plugin, storage, factory, name -> name);
    }

    BoundPluginHost(
            @NonNull PluginHost delegate,
            @NonNull ManagedPlugin plugin,
            @NonNull PluginStorage storage,
            @NonNull PluginStorageFactory factory,
            @NonNull Function<@NonNull String, @NonNull String> toolNames) {
        this.toolNames = toolNames;
        this.delegate = delegate;
        this.plugin = plugin;
        this.storage = storage;
        this.factory = factory;
    }

    private @NonNull String authorize(@NonNull String session) {
        var scope = scopes.get(session);
        if (scope == null) {
            String cursor = null;
            do {
                var page = storage.scopes(PluginStorage.Kind.SESSION, cursor, 200);
                for (var entry : page.entries())
                    if (entry instanceof PluginStorage.SessionScope value
                            && value.sessionId().equals(session)) {
                        scope = value;
                        scopes.put(session, value);
                        break;
                    }
                cursor = page.cursor();
            } while (scope == null && cursor != null);
        }
        if (scope == null) throw new SecurityException("Session does not select this plugin");
        return factory.authorizeSession(storage, scope);
    }

    public void whenReady(@NonNull Runnable callback) {
        delegate.whenReady(() -> plugin.whenActive(callback));
    }

    public @NonNull Invocation invocation(@NonNull String tool) {
        var call = delegate.invocation(resolvedTool(tool));
        authorize(call.sessionId());
        return call;
    }

    public void await(@NonNull String tool, @NonNull PluginAwait wait) {
        invocation(tool);
        delegate.await(resolvedTool(tool), wait);
    }

    private @NonNull String resolvedTool(@NonNull String localId) {
        var context = ToolCallContextHolder.get();
        if (context == null
                || !plugin.bindingId().equals(context.executionPermit().remoteServerName()))
            throw new SecurityException("Invocation does not belong to this plugin instance");
        String resolved = toolNames.apply(localId);
        if (!resolved.equals(context.executionPermit().toolName()))
            throw new SecurityException("Invocation does not match the declared tool");
        return resolved;
    }

    public void publish(
            @NonNull String session, @NonNull String topic, JsonValue.@NonNull ObjectValue facts) {
        authorize(session);
        if (plugin.state() != PluginState.ACTIVE || !topic.matches("[a-z][a-z0-9._-]{0,79}"))
            throw new SecurityException("Plugin publication is unavailable");
        delegate.publish(session, plugin.identity().id() + ":" + topic, facts);
    }

    public void invalidate(@NonNull String session, @NonNull String resource) {
        authorize(session);
        delegate.invalidate(session, resource);
    }

    public void wake(@NonNull String owner, @NonNull String session, @NonNull String agent) {
        if (!authorize(session).equals(owner))
            throw new SecurityException("Session owner mismatch");
        delegate.wake(owner, session, agent);
    }
}
