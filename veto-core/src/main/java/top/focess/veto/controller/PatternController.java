package top.focess.veto.controller;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.vault.KeysteadVault;

@RestController
@RequestMapping("/api/patterns")
public class PatternController {

    private final @NonNull AgentPatternRepository repo;
    private final @NonNull KeysteadVault vault;
    private final @NonNull ModelTierRegistry tierRegistry;

    public
    @NonNull
    PatternController(
            @NonNull AgentPatternRepository repo,
            @NonNull KeysteadVault vault,
            @NonNull ModelTierRegistry tierRegistry) {
        this.repo = repo;
        this.vault = vault;
        this.tierRegistry = tierRegistry;
    }

    @GetMapping
    public List<AgentPatternEntity> list() {
        String user = vault.currentUser();
        return user != null ? repo.findByOwner(user) : List.of();
    }

    @PostMapping
    public @NonNull AgentPatternEntity create(@NonNull @RequestBody Map<String, String> body) {
        String user = vault.currentUser();
        if (user == null) throw new IllegalStateException("Not logged in");
        ModelTier tier = ModelTier.valueOf(body.get("tier").toUpperCase());
        var p = new AgentPatternEntity(body.get("name"), tier, tierRegistry.resolve(tier), user);
        return repo.save(p);
    }

    @DeleteMapping("/{name}")
    public @NonNull ResponseEntity<Void> delete(@NonNull @PathVariable String name) {
        String user = vault.currentUser();
        if (user == null) return ResponseEntity.status(401).build();
        repo.deleteByNameAndOwner(name, user);
        return ResponseEntity.noContent().build();
    }
}
