package top.focess.veto.integration.plugins;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.veto.LlamaCppBridge;

/** Generic, host-granted storage and local-model resources. No plugin earns authority by name. */
@Configuration(proxyBeanMethods = false)
public class HostResourceConfiguration {
    /** Assembles generic vault access and local-model completion when backing beans exist. */
    @Bean
    public @NonNull PluginHostServices pluginHostServices(
            @NonNull ObjectProvider<KeysteadVault> vaultProvider,
            @NonNull ObjectProvider<LlamaCppBridge> bridgeProvider) {
        var services = new HashMap<Class<?>, Object>();
        KeysteadVault vault = vaultProvider.getIfAvailable();
        if (vault != null) services.put(VaultAccess.class, vaultAccess(vault));
        LlamaCppBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge != null) {
            PluginLocalModelFactory factory =
                    plugin -> new BoundLocalModelCompletion(plugin, bridge);
            services.put(PluginLocalModelFactory.class, factory);
        }
        return new PluginHostServices(services);
    }

    private static @NonNull VaultAccess vaultAccess(@NonNull KeysteadVault vault) {
        return arguments -> {
            var invocation = CapabilityAccess.require(ToolCapability.PRIVILEGED);
            var owner = invocation.owner();
            var session = invocation.sessionId();
            if (Thread.currentThread().isInterrupted()
                    || owner == null
                    || session == null
                    || !invocation.executionPermit().call().args().equals(arguments))
                throw denied();
            return new VaultAccess.Handle() {
                private void check() {
                    var current = CapabilityAccess.require(ToolCapability.PRIVILEGED);
                    if (Thread.currentThread().isInterrupted()
                            || current.executionPermit() != invocation.executionPermit()
                            || !owner.equals(current.owner())
                            || !session.equals(current.sessionId())
                            || !invocation.agentId().equals(current.agentId())) throw denied();
                }

                public Scope.@NonNull AgentScope scope() {
                    check();
                    return new Scope.AgentScope(owner, session.toString(), invocation.agentId());
                }

                public boolean isUnlocked() {
                    check();
                    return vault.isUnlocked(owner);
                }

                public @NonNull String createSecureNote(
                        @NonNull String title,
                        @NonNull Map<@NonNull String, @NonNull String> attributes,
                        @NonNull String body) {
                    check();
                    return vault.createSecureNoteIfAbsent(owner, title, attributes, body);
                }
            };
        };
    }

    private static @NonNull SecurityException denied() {
        return new SecurityException("Vault access does not match the authorized invocation");
    }
}
