package top.focess.veto.integration.plugins;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.credentials.CredentialImportAccess;
import top.focess.veto.api.credentials.CredentialWriter;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.veto.LlamaCppBridge;

/** Generic, host-granted storage and local-model resources. No plugin earns authority by name. */
@Configuration(proxyBeanMethods = false)
public class HostResourceConfiguration {
    @Bean
    public @NonNull PluginHostServices pluginHostServices(
            @NonNull ObjectProvider<KeysteadVault> vaultProvider,
            @NonNull ObjectProvider<LlamaCppBridge> bridgeProvider) {
        var services = new HashMap<Class<?>, Object>();
        KeysteadVault vault = vaultProvider.getIfAvailable();
        if (vault != null)
            services.put(
                    ToolDocs.nonNullClass(CredentialImportAccess.class), credentialImport(vault));
        LlamaCppBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge != null) {
            PluginLocalModelFactory factory =
                    plugin -> new BoundLocalModelCompletion(plugin, bridge);
            services.put(ToolDocs.nonNullClass(PluginLocalModelFactory.class), factory);
        }
        return new PluginHostServices(services);
    }

    private static @NonNull CredentialImportAccess credentialImport(@NonNull KeysteadVault vault) {
        return (reference, service, label) -> {
            var invocation = CapabilityAccess.require(ToolCapability.PRIVILEGED);
            var owner = invocation.owner();
            var session = invocation.sessionId();
            var arguments = Map.of("secret_ref", reference, "service", service, "label", label);
            if (Thread.currentThread().isInterrupted()
                    || owner == null
                    || session == null
                    || !invocation.executionPermit().call().args().equals(arguments))
                throw denied();
            CredentialWriter writer =
                    new CredentialWriter() {
                        private void check(@NonNull String requestedOwner) {
                            var current = CapabilityAccess.require(ToolCapability.PRIVILEGED);
                            if (Thread.currentThread().isInterrupted()
                                    || !owner.equals(requestedOwner)
                                    || current.executionPermit() != invocation.executionPermit()
                                    || !owner.equals(current.owner())
                                    || !session.equals(current.sessionId())
                                    || !invocation.agentId().equals(current.agentId())
                                    || !current.executionPermit().call().args().equals(arguments))
                                throw denied();
                        }

                        public boolean isUnlocked(@NonNull String requestedOwner) {
                            check(requestedOwner);
                            return vault.isUnlocked(owner);
                        }

                        public @NonNull String createImportedCredential(
                                @NonNull String requestedOwner,
                                @NonNull String requestedReference,
                                @NonNull String requestedService,
                                @NonNull String requestedLabel,
                                @NonNull String value) {
                            check(requestedOwner);
                            if (!reference.equals(requestedReference)
                                    || !service.equals(requestedService)
                                    || !label.equals(requestedLabel)) throw denied();
                            return vault.createImportedCredential(
                                    owner, reference, service, label, value);
                        }
                    };
            return new CredentialImportAccess.Authorization(
                    owner, session.toString(), invocation.agentId(), writer);
        };
    }

    private static @NonNull SecurityException denied() {
        return new SecurityException("Credential import does not match the authorized invocation");
    }
}
