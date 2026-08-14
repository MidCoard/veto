package top.focess.veto.model.tier;

import org.jspecify.annotations.NonNull;

/**
 * Resolves a {@link ModelTier} to its concrete {@link ModelBinding} from a user's <em>active</em>
 * model-tier profile. This is the single live resolution seam: patterns and agents reference a tier
 * name (TOP/MID/LOW/LOCAL); the registry supplies the provider, model, base URL, and credential-key
 * that the tier currently maps to <em>for that user</em>. Switching the user's active profile
 * ({@code /modeltier use <profile>}) swaps the concrete binding for every caller at the next
 * resolution.
 *
 * <p>Profiles are user-created and per-user: each user configures their own profiles (base URL,
 * model name, provider, credential) and switches between them at runtime. The pattern and the agent
 * only ever know the tier. Resolution is fail-fast - {@link #resolve} throws {@link
 * ModelTierConfigException} when the user has no active profile or the tier is not fully
 * configured, so callers surface a clear "finish setup via /modeltier" message instead of silently
 * falling back.
 */
public interface ModelTierRegistry {

    /**
     * Resolve the user's active profile's binding for the given tier.
     *
     * @param username the session owner (the user whose profile resolves the tier)
     * @param tier the model tier
     * @return the concrete binding for that tier from the user's active profile
     * @throws ModelTierConfigException if the user has no active profile, the profile has no
     *     binding for the tier, or the binding is incomplete (provider/model/credential-key unset)
     */
    @NonNull ModelBinding resolve(@NonNull String username, @NonNull ModelTier tier);

    /**
     * The name of the user's currently active profile (for display).
     *
     * @param username the session owner
     * @return the active profile name, or {@code null} if the user has no active profile
     */
    String activeProfile(@NonNull String username);
}
