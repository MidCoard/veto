package top.focess.veto.model.tier;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.focess.veto.i18n.Msg;
import top.focess.veto.llm.core.ProviderType;

/**
 * The per-user, DB-backed {@link ModelTierRegistry} + {@link ModelTierProfileService}. Profiles and
 * their per-tier bindings live in {@code model_tier_profiles} / {@code model_tier_bindings}; each
 * user owns their own rows. This is the live resolution seam - patterns and agents call {@link
 * #resolve} and receive the concrete binding the user's active profile currently maps that tier to.
 *
 * <p>Switching the active profile ({@code /modeltier use <profile>}) swaps the concrete binding for
 * every one of the user's patterns and agents at the next resolution, without patterns or agents
 * being aware of the change. Resolution is fail-fast: a missing active profile, a missing tier
 * binding, or an incomplete binding (provider/model/credential-key unset) throws {@link
 * ModelTierConfigException} so callers tell the user to finish setup via {@code /modeltier} instead
 * of silently running on a hardcoded default.
 */
@Service
public class DefaultModelTierService implements ModelTierRegistry, ModelTierProfileService {

    /** Default sampling temperature when a binding leaves it unset. */
    private static final double DEFAULT_TEMPERATURE = 0.7;

    /** Default max output tokens when a binding leaves it unset. */
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;

    private final @NonNull ModelTierProfileRepository profileRepo;
    private final @NonNull ModelTierBindingRepository bindingRepo;
    private final @NonNull CredentialExistenceChecker credentialChecker;

    public DefaultModelTierService(
            @NonNull ModelTierProfileRepository profileRepo,
            @NonNull ModelTierBindingRepository bindingRepo,
            @NonNull CredentialExistenceChecker credentialChecker) {
        this.profileRepo = profileRepo;
        this.bindingRepo = bindingRepo;
        this.credentialChecker = credentialChecker;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull ModelBinding resolve(@NonNull String username, @NonNull ModelTier tier) {
        ModelTierProfileEntity profile = activeProfileEntity(username);
        ModelTierBindingEntity binding =
                bindingRepo
                        .findByProfileIdAndTier(profile.getId(), tier)
                        .orElseThrow(
                                () ->
                                        new ModelTierConfigException(
                                                Msg.get(
                                                        "error.tier.noBinding",
                                                        profile.getName(),
                                                        tier)));
        var provider = binding.getProvider();
        String model = binding.getModel();
        String credentialKey = binding.getCredentialKey();
        if (provider == null || model == null || credentialKey == null) {
            throw new ModelTierConfigException(
                    Msg.get("error.tier.incomplete", tier, profile.getName()));
        }
        Double configuredTemperature = binding.getTemperature();
        double temperature =
                configuredTemperature == null ? DEFAULT_TEMPERATURE : configuredTemperature;
        Integer configuredMaxOutputTokens = binding.getMaxOutputTokens();
        int maxOutputTokens =
                configuredMaxOutputTokens == null
                        ? DEFAULT_MAX_OUTPUT_TOKENS
                        : configuredMaxOutputTokens;
        return new ModelBinding(
                provider, model, credentialKey, temperature, maxOutputTokens, binding.getBaseUrl());
    }

    @Override
    @Transactional(readOnly = true)
    public String activeProfile(@NonNull String username) {
        return profileRepo
                .findByOwnerAndActiveTrue(username)
                .map(ModelTierProfileEntity::getName)
                .orElse(null);
    }

    private @NonNull ModelTierProfileEntity activeProfileEntity(@NonNull String username) {
        return profileRepo
                .findByOwnerAndActiveTrue(username)
                .orElseThrow(
                        () ->
                                new ModelTierConfigException(
                                        Msg.get("error.tier.noActiveProfile", username)));
    }

    @Override
    @Transactional
    public void createProfile(@NonNull String username, @NonNull String name) {
        if (profileRepo.findByNameAndOwner(name, username).isPresent()) {
            throw new IllegalArgumentException(Msg.get("error.tier.profileExists", name));
        }
        // Auto-activate the user's first profile so a first-time user can resolve immediately
        // without a separate /modeltier use (they can switch later with `use`).
        boolean autoActive = profileRepo.findByOwnerAndActiveTrue(username).isEmpty();
        profileRepo.save(new ModelTierProfileEntity(name, username, autoActive));
    }

    @Override
    @Transactional
    public void setField(
            @NonNull String username,
            @NonNull String profileName,
            @NonNull ModelTier tier,
            @NonNull ModelTierField field,
            @NonNull String value) {
        ModelTierProfileEntity profile = requireProfile(username, profileName);
        ModelTierBindingEntity binding =
                bindingRepo
                        .findByProfileIdAndTier(profile.getId(), tier)
                        .orElseGet(() -> new ModelTierBindingEntity(profile.getId(), tier));
        if (field == ModelTierField.CREDENTIAL_KEY) {
            String credKey = value.trim();
            if (!credentialChecker.exists(username, credKey)) {
                throw new IllegalArgumentException(Msg.get("error.vault.credKeyMissing", credKey));
            }
        }
        applyField(binding, field, value);
        bindingRepo.save(binding);
    }

    @Override
    @Transactional
    public void activateProfile(@NonNull String username, @NonNull String name) {
        ModelTierProfileEntity profile = requireProfile(username, name);
        // Deactivate the user's other profiles first so at most one is active.
        for (ModelTierProfileEntity other : profileRepo.findByOwner(username)) {
            if (other.isActive() && !other.getId().equals(profile.getId())) {
                other.setActive(false);
                profileRepo.save(other);
            }
        }
        profile.setActive(true);
        profileRepo.save(profile);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ModelTierProfileEntity> listProfiles(@NonNull String username) {
        return profileRepo.findByOwner(username);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<ModelTierProfileEntity> profile(
            @NonNull String username, @NonNull String name) {
        return profileRepo.findByNameAndOwner(name, username);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ModelTierBindingEntity> bindings(
            @NonNull String username, @NonNull String profileName) {
        ModelTierProfileEntity profile = requireProfile(username, profileName);
        return bindingRepo.findByProfileId(profile.getId());
    }

    @Override
    @Transactional
    public boolean deleteProfile(@NonNull String username, @NonNull String name) {
        Optional<ModelTierProfileEntity> opt = profileRepo.findByNameAndOwner(name, username);
        if (opt.isEmpty()) {
            return false;
        }
        ModelTierProfileEntity profile = opt.get();
        bindingRepo.deleteByProfileId(profile.getId());
        profileRepo.deleteByNameAndOwner(name, username);
        return true;
    }

    private @NonNull ModelTierProfileEntity requireProfile(
            @NonNull String username, @NonNull String name) {
        return profileRepo
                .findByNameAndOwner(name, username)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        Msg.get("error.tier.profileNotFound", name)));
    }

    private static void applyField(
            @NonNull ModelTierBindingEntity binding,
            @NonNull ModelTierField field,
            @NonNull String value) {
        switch (field) {
            case PROVIDER -> {
                try {
                    binding.setProvider(ProviderType.valueOf(value.trim().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            Msg.get("error.tier.unknownProvider", value));
                }
            }
            case BASE_URL -> binding.setBaseUrl(value.isBlank() ? null : value.trim());
            case MODEL -> binding.setModel(value.trim());
            case CREDENTIAL_KEY -> binding.setCredentialKey(value.trim());
            case TEMPERATURE -> {
                try {
                    binding.setTemperature(Double.parseDouble(value.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            Msg.get("error.tier.invalidTemperature", value));
                }
            }
            case MAX_OUTPUT_TOKENS -> {
                try {
                    binding.setMaxOutputTokens(Integer.parseInt(value.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            Msg.get("error.tier.invalidMaxTokens", value));
                }
            }
        }
    }
}
