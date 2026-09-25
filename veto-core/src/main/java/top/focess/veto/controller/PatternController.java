package top.focess.veto.controller;

import java.util.List;
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
 * Per-user agent patterns: named model-tier choices whose concrete binding is resolved from the
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
        String user = vault.currentUser();
        return user != null ? repo.findByOwner(user) : List.of();
    }

    /**
     * Creates a pattern for the current user, resolving its model binding from the active tier
     * profile. 400 on missing fields, unknown tier, or an unconfigured profile; 409 on a duplicate
     * name.
     */
    @PostMapping
    public @NonNull AgentPatternEntity create(@RequestBody @NonNull CreatePatternRequest body) {
        String user = vault.currentUser();
        if (user == null) throw new IllegalStateException(Msg.get("error.auth.notLoggedIn"));
        String name = body.name();
        String tierValue = body.tier();
        if (name == null || name.isBlank() || tierValue == null || tierValue.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.pattern.missingFields"));
        }
        // Names are unique per owner - a duplicate insert would poison findByNameAndOwner
        // (NonUniqueResultException) for every later session create against this name.
        if (repo.existsByNameAndOwner(name, user)) {
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
            binding = tierRegistry.resolve(user, tier);
        } catch (ModelTierConfigException e) {
            // No active model-tier profile (or incomplete binding) for this user - the client must
            // configure one before creating a pattern that targets this tier.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        var p = new AgentPatternEntity(name, tier, binding, user);
        return repo.save(p);
    }

    /** Deletes the current user's pattern with the given name; a missing name is a no-op. */
    @DeleteMapping("/{name}")
    public @NonNull ResponseEntity<?> delete(@PathVariable @NonNull String name) {
        String user = vault.currentUser();
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(
                            new StatusMessageResponse(
                                    "error", Msg.get("error.auth.notAuthenticated")));
        }
        repo.deleteByNameAndOwner(name, user);
        return ResponseEntity.noContent().build();
    }
}
