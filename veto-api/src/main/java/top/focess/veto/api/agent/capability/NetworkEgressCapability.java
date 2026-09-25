package top.focess.veto.api.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.credentials.ImportedCredentialLease;
import top.focess.veto.api.http.ApprovedHttpDestination;

/** Host-mediated network and credential access authorized for the current invocation. */
public interface NetworkEgressCapability extends Capability {
    /**
     * Opens the imported credential selected by an approved tool argument.
     *
     * @param argument argument name carrying the credential reference
     * @param service service that will consume the credential
     * @return a call-scoped credential lease
     */
    @NonNull ImportedCredentialLease openImportedCredential(
            @NonNull String argument, @NonNull String service);

    /**
     * Opens the HTTP destination selected by an approved tool argument.
     *
     * @param argument argument name carrying the destination URL
     * @return a call-scoped destination that mediates HTTP requests
     */
    @NonNull ApprovedHttpDestination openApprovedDestination(@NonNull String argument);
}
