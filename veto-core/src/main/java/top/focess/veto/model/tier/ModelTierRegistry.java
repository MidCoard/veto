package top.focess.veto.model.tier;

import org.jspecify.annotations.NonNull;

/**
 * Resolves a {@link ModelTier} to its concrete {@link ModelBinding} from the <em>active</em>
 * model-tier configuration. This is the single live resolution seam: patterns and agents reference
 * a tier name (TOP/MID/LOW/LOCAL); the registry supplies the provider, model, and credential-key
 * that the tier currently maps to. Switching the active configuration swaps the concrete binding
 * for every caller at once.
 *
 * <p>The default backing is {@code veto.model-tiers} YAML ({@link ModelTierProperties}); a future
 * DB-backed profile can replace this bean without touching callers.
 */
public interface ModelTierRegistry {

    /**
     * Resolve the active configuration's binding for the given tier. Never returns {@code null} -
     * falls back to a sane default when the tier is unset.
     *
     * @param tier the model tier
     * @return the concrete binding for that tier from the active configuration
     */
    @NonNull ModelBinding resolve(@NonNull ModelTier tier);

    /**
     * The name of the currently active model-tier configuration.
     *
     * @return the active configuration name
     */
    @NonNull String activeProfile();
}
