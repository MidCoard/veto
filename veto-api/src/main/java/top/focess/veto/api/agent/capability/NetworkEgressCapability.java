package top.focess.veto.api.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.credentials.ImportedCredentialLease;
import top.focess.veto.api.http.ApprovedHttpDestination;

public interface NetworkEgressCapability extends Capability {
    @NonNull ImportedCredentialLease openImportedCredential(
            @NonNull String argument, @NonNull String service);

    @NonNull ApprovedHttpDestination openApprovedDestination(@NonNull String argument);
}
