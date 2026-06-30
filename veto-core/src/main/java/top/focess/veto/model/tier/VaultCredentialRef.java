package top.focess.veto.model.tier;

import org.jspecify.annotations.NonNull;

public record VaultCredentialRef(
        @NonNull String userId, @NonNull Provider provider, @NonNull String credentialKey) {}
