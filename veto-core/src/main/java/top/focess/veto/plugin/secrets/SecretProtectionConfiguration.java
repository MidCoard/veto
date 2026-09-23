package top.focess.veto.plugin.secrets;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.plugin.runtime.PluginHostServices;
import top.focess.veto.secret.api.CredentialImportAccess;
import top.focess.veto.secret.api.CredentialWriter;
import top.focess.veto.secret.api.SecretDetectionModel;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.veto.LlamaCppBridge;

/**
 * Host authority for the secret-protection plugin: the vault-backed credential-import gate and the
 * SLM detection completion, delivered through the generic plugin-context host-service lookup. The
 * plugin is a runtime-only dependency — when its jar is absent this bean backs off and imports fail
 * at call time.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "top.focess.veto.secret.api.CredentialImportAccess")
public class SecretProtectionConfiguration {
    @Bean
    @SuppressWarnings(
            "nullness") // Checker Framework treats these cross-module class literals as nullable.
    public @NonNull PluginHostServices pluginHostServices(
            @NonNull ObjectProvider<KeysteadVault> vaultProvider,
            @NonNull ObjectProvider<LlamaCppBridge> bridgeProvider) {
        var services = new HashMap<Class<?>, Object>();
        KeysteadVault vault = vaultProvider.getIfAvailable();
        if (vault != null) {
            // Without a vault, imports fail at call time (degraded semantics).
            var writer =
                    new CredentialWriter() {
                        public boolean isUnlocked(@NonNull String owner) {
                            return vault.isUnlocked(owner);
                        }

                        public @NonNull String createImportedCredential(
                                @NonNull String owner,
                                @NonNull String reference,
                                @NonNull String service,
                                @NonNull String label,
                                @NonNull String value) {
                            return vault.createImportedCredential(
                                    owner, reference, service, label, value);
                        }
                    };
            CredentialImportAccess access =
                    (reference, service, label) -> {
                        var context = CapabilityAccess.require(ToolCapability.PRIVILEGED);
                        var owner = context.owner();
                        var session = context.sessionId();
                        if (owner == null
                                || session == null
                                || !context.executionPermit()
                                        .call()
                                        .args()
                                        .equals(
                                                Map.of(
                                                        "secret_ref",
                                                        reference,
                                                        "service",
                                                        service,
                                                        "label",
                                                        label)))
                            throw new SecurityException(
                                    "Credential import does not match the authorized call");
                        return new CredentialImportAccess.Authorization(
                                owner, session.toString(), context.agentId(), writer);
                    };
            services.put(CredentialImportAccess.class, access);
        }
        LlamaCppBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge != null && bridge.isAvailable()) {
            services.put(
                    SecretDetectionModel.class,
                    new SecretDetectionModel() {
                        @Override
                        public boolean isAvailable() {
                            return bridge.isAvailable();
                        }

                        @Override
                        public @NonNull Optional<String> complete(
                                @NonNull String source, @NonNull Map<String, ?> data) {
                            if (!bridge.isAvailable()) return Optional.empty();
                            try {
                                return Optional.ofNullable(
                                        bridge.infer(
                                                        PromptCompiler.compileText(source, data),
                                                        "veto-secret-detect")
                                                .get(2, TimeUnit.SECONDS));
                            } catch (Exception failure) {
                                return Optional.empty();
                            }
                        }
                    });
        }
        return new PluginHostServices(services);
    }
}
