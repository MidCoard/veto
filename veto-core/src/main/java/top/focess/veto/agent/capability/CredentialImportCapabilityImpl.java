package top.focess.veto.agent.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.secret.api.CredentialWriter;
import top.focess.veto.secret.references.SecretCandidateStore;
import top.focess.veto.vault.KeysteadVault;

public final class CredentialImportCapabilityImpl implements CredentialImportCapability {
    private final @NonNull SecretCandidateStore candidates;
    private final @NonNull CredentialWriter writer;
    private final @NonNull ObjectMapper mapper;

    public CredentialImportCapabilityImpl(
            @NonNull SecretCandidateStore candidates,
            @NonNull KeysteadVault vault,
            @NonNull ObjectMapper mapper) {
        this.candidates = candidates;
        this.writer =
                new CredentialWriter() {
                    @Override
                    public boolean isUnlocked(@NonNull String owner) {
                        return vault.isUnlocked(owner);
                    }

                    @Override
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
        this.mapper = mapper;
    }

    @Override
    public @NonNull String importDetected(
            @NonNull String reference, @NonNull String service, @NonNull String label) {
        var context =
                CapabilityAccess.require(
                        ToolCapability.CREDENTIAL_IMPORT);
        String owner = context.owner();
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
                                        label))) {
            throw new SecurityException("Credential import does not match the authorized call");
        }
        var receipt =
                candidates.importOnce(
                        new SecretCandidateStore.Scope(
                                owner, session.toString(), context.agentId()),
                        reference,
                        service,
                        label,
                        writer);
        try {
            return mapper.writeValueAsString(
                    Map.of(
                            "credential_ref",
                            receipt.credentialRef(),
                            "service",
                            receipt.service(),
                            "label",
                            receipt.label(),
                            "status",
                            "created"));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot render credential import receipt");
        }
    }
}
