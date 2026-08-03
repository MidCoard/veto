package top.focess.veto.model.tier;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * The write side of the per-user model-tier configuration: create profiles, configure a tier's
 * binding one field at a time, switch the active profile, and list / show / delete profiles. The
 * read side ({@link ModelTierRegistry#resolve}) is the live resolution seam; this is the management
 * surface driven by the {@code /modeltier} command.
 *
 * <p>Each {@code set} call updates a single field of a single tier's binding (the per-field
 * "optional" model) - a user builds a binding incrementally: {@code /modeltier set default TOP
 * provider DEEPSEEK}, {@code ... model deepseek-chat}, {@code ... credKey deepseek-default}, and so
 * on. A binding may be partial between calls; {@link ModelTierRegistry#resolve} fail-fasts if a
 * required field is still unset.
 */
public interface ModelTierProfileService {

    /**
     * Create a new profile for the user. New profiles start inactive; if the user has no active
     * profile yet, the new one is auto-activated (so a first-time user can resolve immediately
     * without a separate {@code use}).
     *
     * @throws IllegalArgumentException if a profile with this name already exists for the user
     */
    void createProfile(@NonNull String username, @NonNull String name);

    /**
     * Set one field of one tier's binding within a profile, upserting the binding row.
     *
     * @param username the session owner
     * @param profileName the profile name (must exist)
     * @param tier the tier whose binding is being configured
     * @param field the field to set
     * @param value the field's new value (parsed per field; a blank base URL clears it)
     * @throws IllegalArgumentException if the profile does not exist, the value is invalid for the
     *     field (e.g. unknown provider, non-numeric temperature), or the field is {@code
     *     CREDENTIAL_KEY} and no credential with that key is stored in the user's vault
     */
    void setField(
            @NonNull String username,
            @NonNull String profileName,
            @NonNull ModelTier tier,
            @NonNull ModelTierField field,
            @NonNull String value);

    /**
     * Activate a profile (deactivate the user's other profiles). The active profile is the one
     * {@link ModelTierRegistry#resolve} reads.
     *
     * @throws IllegalArgumentException if the profile does not exist
     */
    void activateProfile(@NonNull String username, @NonNull String name);

    /** All profiles owned by the user. */
    @NonNull List<ModelTierProfileEntity> listProfiles(@NonNull String username);

    /** A profile by name within the user's profiles. */
    @NonNull Optional<ModelTierProfileEntity> profile(
            @NonNull String username, @NonNull String name);

    /** The bindings for one of the user's profiles (for {@code /modeltier show}). */
    @NonNull List<ModelTierBindingEntity> bindings(
            @NonNull String username, @NonNull String profileName);

    /**
     * Delete a profile and its bindings. Returns false if the profile does not exist.
     *
     * @return true if a profile was deleted
     */
    boolean deleteProfile(@NonNull String username, @NonNull String name);
}
