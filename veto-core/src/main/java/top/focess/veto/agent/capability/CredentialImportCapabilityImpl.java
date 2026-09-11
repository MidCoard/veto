package top.focess.veto.agent.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.SecretCandidateStore;

@Component
public final class CredentialImportCapabilityImpl implements CredentialImportCapability {
    private final @NonNull SecretCandidateStore candidates;
    private final @NonNull KeysteadVault vault;
    private final @NonNull ObjectMapper mapper;

    public CredentialImportCapabilityImpl(
            @NonNull SecretCandidateStore candidates,
            @NonNull KeysteadVault vault,
            @NonNull ObjectMapper mapper) {
        this.candidates = candidates;
        this.vault = vault;
        this.mapper = mapper;
    }

    @Override
    public @NonNull String importDetected(
            @NonNull String reference, @NonNull String service, @NonNull String label) {
        var receipt = candidates.importApproved(reference, service, label, vault);
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
