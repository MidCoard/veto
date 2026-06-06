package top.focess.veto.controller;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.vault.CredentialVault;

@RestController
@RequestMapping("/api/patterns")
public class PatternController {

    private final AgentPatternRepository repo;
    private final CredentialVault vault;

    public PatternController(AgentPatternRepository repo, CredentialVault vault) {
        this.repo = repo;
        this.vault = vault;
    }

    @GetMapping
    public List<AgentPatternEntity> list() {
        String user = vault.getCurrentUser();
        return user != null ? repo.findByOwner(user) : List.of();
    }

    @PostMapping
    public AgentPatternEntity create(@RequestBody Map<String, String> body) {
        String user = vault.getCurrentUser();
        if (user == null) throw new IllegalStateException("Not logged in");
        var p =
                new AgentPatternEntity(
                        body.get("name"),
                        body.get("provider").toUpperCase(),
                        body.get("model"),
                        "pattern-" + body.get("name"),
                        body.getOrDefault("systemPrompt", "You are a helpful assistant."),
                        user);
        vault.store(p.getCredentialKey(), body.get("apiKey"));
        return repo.save(p);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
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
