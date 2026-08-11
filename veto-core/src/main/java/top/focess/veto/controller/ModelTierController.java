package top.focess.veto.controller;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.i18n.Msg;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierBindingEntity;
import top.focess.veto.model.tier.ModelTierField;
import top.focess.veto.model.tier.ModelTierProfileEntity;
import top.focess.veto.model.tier.ModelTierProfileService;
import top.focess.veto.vault.KeysteadVault;

/**
 * REST surface for per-user model-tier profiles - the web equivalent of the terminal {@code
 * /modeltier} command. Profiles map tiers (TOP/MID/LOW/LOCAL) to concrete bindings (provider /
 * baseUrl / model / credential-key / sampling); exactly one profile is active, and {@code
 * ModelTierRegistry.resolve} reads the active one, so an "activate" here swaps the concrete model
 * for every pattern and agent the user owns at the next resolution.
 */
@RestController
@RequestMapping("/api/modeltiers")
public class ModelTierController {

    private final @NonNull ModelTierProfileService profiles;
    private final @NonNull KeysteadVault vault;

    public ModelTierController(
            @NonNull ModelTierProfileService profiles, @NonNull KeysteadVault vault) {
        this.profiles = profiles;
        this.vault = vault;
    }

    @GetMapping
    public @NonNull List<Map<String, Object>> list() {
        return profiles.listProfiles(requireUser()).stream()
                .map(ModelTierController::profileView)
                .toList();
    }

    @PostMapping
    public @NonNull Map<String, Object> create(@NonNull @RequestBody Map<String, String> body) {
        String user = requireUser();
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.tier.nameRequired"));
        }
        try {
            profiles.createProfile(user, name.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return profiles.profile(user, name.trim())
                .map(ModelTierController::profileView)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.INTERNAL_SERVER_ERROR,
                                        Msg.get("error.tier.profileMissingAfterCreate")));
    }

    @PostMapping("/{name}/activate")
    public @NonNull ResponseEntity<Void> activate(@NonNull @PathVariable String name) {
        String user = requireUser();
        try {
            profiles.activateProfile(user, name);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{name}")
    public @NonNull ResponseEntity<Void> delete(@NonNull @PathVariable String name) {
        String user = requireUser();
        if (!profiles.deleteProfile(user, name)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, Msg.get("error.tier.noProfile", name));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{name}/bindings")
    public @NonNull List<Map<String, Object>> bindings(@NonNull @PathVariable String name) {
        String user = requireUser();
        if (profiles.profile(user, name).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, Msg.get("error.tier.noProfile", name));
        }
        return profiles.bindings(user, name).stream()
                .map(ModelTierController::bindingView)
                .toList();
    }

    /**
     * Set one or more fields of one tier's binding. Body keys are the wire field names ({@code
     * provider}, {@code baseUrl}, {@code model}, {@code credKey}, {@code temp}, {@code max}); only
     * present keys are touched, so a partial update is a valid call. Each field is validated by the
     * service (unknown provider, non-numeric temp, credKey not in the vault) - the first invalid
     * field fails the whole call with a 400 naming the field.
     */
    @PutMapping("/{name}/bindings/{tier}")
    public @NonNull ResponseEntity<Void> setBinding(
            @NonNull @PathVariable String name,
            @NonNull @PathVariable String tier,
            @NonNull @RequestBody Map<String, String> body) {
        String user = requireUser();
        ModelTier parsedTier;
        try {
            parsedTier = ModelTier.valueOf(tier.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.tier.unknownTier", tier));
        }
        if (profiles.profile(user, name).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, Msg.get("error.tier.noProfile", name));
        }
        if (body.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.tier.noFields"));
        }
        for (Map.Entry<String, String> entry : body.entrySet()) {
            ModelTierField field = ModelTierField.fromField(entry.getKey());
            if (field == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, Msg.get("error.tier.unknownField", entry.getKey()));
            }
            try {
                profiles.setField(user, name, parsedTier, field, entry.getValue());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        Msg.get("error.tier.fieldInvalid", field.field(), e.getMessage()));
            }
        }
        return ResponseEntity.noContent().build();
    }

    private static @NonNull Map<String, Object> profileView(@NonNull ModelTierProfileEntity p) {
        return Map.of(
                "name", p.getName(),
                "active", p.isActive(),
                "createdAt", p.getCreatedAt().toString());
    }

    private static @NonNull Map<String, Object> bindingView(@NonNull ModelTierBindingEntity b) {
        // A hand-built map (not Map.of) because binding fields are nullable mid-configuration.
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("tier", b.getTier().name());
        view.put("provider", b.getProvider() == null ? null : b.getProvider().name());
        view.put("baseUrl", b.getBaseUrl());
        view.put("model", b.getModel());
        view.put("credKey", b.getCredentialKey());
        view.put("temp", b.getTemperature());
        view.put("max", b.getMaxOutputTokens());
        return view;
    }

    private @NonNull String requireUser() {
        String user = vault.currentUser();
        if (user == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, Msg.get("error.auth.notLoggedIn"));
        }
        return user;
    }
}
