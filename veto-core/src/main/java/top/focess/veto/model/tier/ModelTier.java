package top.focess.veto.model.tier;

/**
 * A capability tier that patterns and agents bind to instead of a concrete model. Each tier
 * resolves live, per user, to a {@link ModelBinding} via the user's active model-tier profile (see
 * {@link ModelTierRegistry#resolve}).
 */
public enum ModelTier {
    TOP,
    MID,
    LOW,
    LOCAL
}
