package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;

public sealed interface CredentialImportCapability extends Capability
        permits CredentialImportCapabilityImpl {
    @NonNull String importDetected(
            @NonNull String reference, @NonNull String service, @NonNull String label);
}
