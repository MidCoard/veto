package top.focess.veto.controller;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.vault.CredentialVault;

@RestController
@RequestMapping("/api/patterns")
public class PatternController {

    private final @NonNull AgentPatternRepository repo;
    private final @NonNull CredentialVault vault;

    public
    @NonNull
    PatternController(@NonNull AgentPatternRepository repo, @NonNull CredentialVault vault) {
        this.repo = repo;
        this.vault = vault;
    }

    @GetMapping
    public List<AgentPatternEntity> list() {
        String user = vault.getCurrentUser();
        return user != null ? repo.findByOwner(user) : List.of();
    }

    @PostMapping
    public @NonNull AgentPatternEntity create(@NonNull @RequestBody Map<String, String> body) {
        String user = vault.getCurrentUser();
        if (user == null) throw new IllegalStateException("Not logged in");
        String topModel = body.getOrDefault("topModel", body.get("model"));
        String midModel = body.get("midModel");
        String lowModel = body.get("lowModel");
        var p =
                new AgentPatternEntity(
                        body.get("name"),
                        body.get("provider").toUpperCase(),
                        body.get("model"),
                        "pattern-" + body.get("name"),
                        user,
                        topModel,
                        midModel,
                        lowModel);
        vault.store(p.getCredentialKey(), body.get("apiKey"));
        return repo.save(p);
    }

    @DeleteMapping("/{name}")
    public @NonNull ResponseEntity<Void> delete(@NonNull @PathVariable String name) {
        String user = vault.getCurrentUser();
        if (user == null) return ResponseEntity.status(401).build();
        repo.deleteByNameAndOwner(name, user);
        try {
            vault.delete("pattern-" + name);
        } catch (Exception ignored) {
        }
        return ResponseEntity.noContent().build();
    }
}
