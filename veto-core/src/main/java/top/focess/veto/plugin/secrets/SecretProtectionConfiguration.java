package top.focess.veto.plugin.secrets;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.secret.SecretProtectionPlugin;
import top.focess.veto.secret.api.CredentialImportAccess;
import top.focess.veto.secret.api.CredentialWriter;
import top.focess.veto.secret.references.SecretCandidateStore;
import top.focess.veto.vault.KeysteadVault;

/** Binds the standalone secret-protection module to Veto's application lifecycle. */
@Configuration(proxyBeanMethods = false)
public class SecretProtectionConfiguration {
    @Bean(destroyMethod = "")
    public @NonNull SecretProtectionPlugin secretProtectionPlugin(
            @NonNull SecretCandidateStore candidates,
            @NonNull ObjectProvider<KeysteadVault> vaultProvider) {
        KeysteadVault vault = vaultProvider.getIfAvailable();
        if (vault == null) {
            // Degraded install (no vault in this context): imports fail at call time.
            return new SecretProtectionPlugin(candidates);
        }
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
        return new SecretProtectionPlugin(
                candidates,
                (reference, service, label) -> {
                    var context = CapabilityAccess.require(ToolCapability.CREDENTIAL_IMPORT);
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
                            new SecretCandidateStore.Scope(
                                    owner, session.toString(), context.agentId()),
                            writer);
                });
    }

    /** Shared across input capture, protected file reads, import, and session/auth lifecycle. */
    @Bean
    public @NonNull SecretCandidateStore secretCandidateStore() {
        return new SecretCandidateStore();
    }

    @Bean
    public @NonNull CandidateExpiry secretCandidateExpiry(
            @NonNull SecretCandidateStore candidates) {
        return new CandidateExpiry(candidates);
    }

    /** Scheduling belongs to the host; the module only owns expiration semantics. */
    public static final class CandidateExpiry {
        private final @NonNull SecretCandidateStore candidates;

        CandidateExpiry(@NonNull SecretCandidateStore candidates) {
            this.candidates = candidates;
        }

        @Scheduled(fixedDelay = 60_000)
        public void expire() {
            candidates.expire();
        }
    }
}
