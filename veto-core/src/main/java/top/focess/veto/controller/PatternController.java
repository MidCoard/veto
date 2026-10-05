package top.focess.veto.controller;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.controller.dto.*;
import top.focess.veto.i18n.Msg;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierConfigException;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;

/**
 * Per-userId agent patterns: named model-tier choices whose concrete binding is resolved from the
 * user's active tier profile at creation time and reused by session creation.
 */
@RestController
@RequestMapping("/api/patterns")
public class PatternController {

    private final @NonNull AgentPatternRepository repo;
    private final @NonNull KeysteadVault vault;
    private final @NonNull ModelTierRegistry tierRegistry;

    /** Creates the controller with the pattern repository, vault, and tier registry. */
    public PatternController(
            @NonNull AgentPatternRepository repo,
            @NonNull KeysteadVault vault,
            @NonNull ModelTierRegistry tierRegistry) {
        this.repo = repo;
        this.vault = vault;
        this.tierRegistry = tierRegistry;
    }

    /** Lists the current user's patterns; empty when not logged in. */
    @GetMapping
    public @NonNull List<AgentPatternEntity> list() {
        UUID userId = vault.currentUser();
        return userId != null ? repo.findByUserId(userId) : List.of();
    }

    /**
     * Creates a pattern for the current userId, resolving its model binding from the active tier
     * profile. 400 on missing fields, unknown tier, or an unconfigured profile; 409 on a duplicate
     * name.
     */
    @PostMapping
    public @NonNull AgentPatternEntity create(@RequestBody @NonNull CreatePatternRequest body) {
        UUID userId = vault.currentUser();
        if (userId == null) throw new IllegalStateException(Msg.get("error.auth.notLoggedIn"));
        String name = body.name();
        String tierValue = body.tier();
        if (name == null || name.isBlank() || tierValue == null || tierValue.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.pattern.missingFields"));
        }
        // Names are unique per user - a duplicate insert would poison findByNameAndUserId
        // (NonUniqueResultException) for every later session create against this name.
        if (repo.existsByNameAndUserId(name, userId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, Msg.get("error.pattern.duplicate", name));
        }
        ModelTier tier;
        try {
            tier = Nullness.requireNonNull(ModelTier.valueOf(tierValue.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.pattern.missingFields"));
        }
        ModelBinding binding;
        try {
            binding = tierRegistry.resolve(userId, tier);
        } catch (ModelTierConfigException e) {
            // No active model-tier profile (or incomplete binding) for this userId - the client
            // must
            // configure one before creating a pattern that targets this tier.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        var p = new AgentPatternEntity(name, tier, binding, userId);
        return repo.save(p);
    }

    /** Deletes the current user's pattern with the given name; a missing name is a no-op. */
    @DeleteMapping("/{name}")
    public @NonNull ResponseEntity<?> delete(@PathVariable @NonNull String name) {
        UUID userId = vault.currentUser();
        if (userId == null) {
            return ResponseEntity.status(401)
                    .body(
                            new StatusMessageResponse(
                                    "error", Msg.get("error.auth.notAuthenticated")));
        }
        repo.deleteByNameAndUserId(name, userId);
        return ResponseEntity.noContent().build();
    }
}
