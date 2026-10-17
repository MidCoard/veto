package top.focess.veto.integration.plugins;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.sandbox.SandboxManager;

/** Generic admitted process resources. Feature registries and task policy are outside core. */
@Component
public final class PluginProcessHosts implements PluginProcessHostFactory {
    private final @NonNull SandboxManager sandbox;
    private final @NonNull PluginStorageFactory scopes;
    private final @NonNull SessionAgentRegistry agents;

    /** Creates the factory over the sandbox, storage-grant, and agent-registry services. */
    public PluginProcessHosts(
            @NonNull SandboxManager sandbox,
            @NonNull PluginStorageFactory scopes,
            @NonNull SessionAgentRegistry agents) {
        this.sandbox = sandbox;
        this.scopes = scopes;
        this.agents = agents;
    }

    /** Exposes this factory as a host service so the manager can bind it per plugin. */
    @Bean
    public @NonNull PluginHostServices processHostServices() {
        return new PluginHostServices(Map.of(PluginProcessHostFactory.class, this));
    }

    /** Returns the process host for one plugin activation and its scoped storage. */
    public @NonNull ProcessHost bind(
            @NonNull ManagedPlugin plugin, @NonNull PluginStorage storage) {
        return new PluginProcessHost(plugin, storage, sandbox, scopes, agents);
    }

    /** Verifies the running process belongs to the invocation's grant and may still take input. */
    public static void validateInput(
            @NonNull ManagedPlugin plugin,
            PluginHost.@NonNull Invocation invocation,
            ProcessHost.@NonNull Running running) {
        if (!(running instanceof RunningPluginProcess process)
                || process.owner.plugin != plugin
                || !process.invocation.userId().equals(invocation.userId())
                || !process.invocation.sessionId().equals(invocation.sessionId())
                || !process.invocation.agentId().equals(invocation.agentId()))
            throw new SecurityException("Process belongs to another scope");
        process.checkInput();
    }
}
