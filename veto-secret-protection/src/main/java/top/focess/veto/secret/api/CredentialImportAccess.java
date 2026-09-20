package top.focess.veto.secret.api;

import org.jspecify.annotations.NonNull;
import top.focess.veto.secret.references.SecretCandidateStore;

/** Trusted host authorization boundary. No secret values leave the local storage port. */
@FunctionalInterface
public interface CredentialImportAccess {
    record Authorization(
            SecretCandidateStore.@NonNull Scope scope, @NonNull CredentialWriter writer) {}

    @NonNull Authorization authorize(
            @NonNull String reference, @NonNull String service, @NonNull String label);
}
