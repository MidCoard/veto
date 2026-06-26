package top.focess.veto.model.tier;

public interface ModelTierRegistry {
    /**
     * Resolve the active pattern's modelId for the given tier to a concrete ModelBinding. The
     * pattern supplies the modelId per tier (topModel required; midModel/lowModel default to
     * topModel). The provider + defaultTemperature + maxOutputTokens come from the global model
     * catalog.
     */
    ModelBinding resolve(String patternName, ModelTier tier);

    /**
     * The Vault-keyed credential handle for the resolved provider (per-user; key never in clear).
     */
    VaultCredentialRef credential(String userId, Provider provider);
}
