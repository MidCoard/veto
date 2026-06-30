package top.focess.veto.agent.skills;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The skill registry — lookup + SHA-256 integrity verification. Returns the full {@code SKILL.md}
 * body as a tool observation on {@code load_skill}.
 *
 * <p>Scans PERSONAL ({@code ~/.veto/skills/<name>/}) and an optional PROJECT ({@code
 * <workspace>/.veto/skills/<name>/}) directory at startup. NATIVE skills (shipped with Veto, hash
 * pre-seeded at install time) are a deployer-installation concern — the registry scans the on-disk
 * personal/project dirs; bundling native SKILL.md resources is noted as a follow-up.
 */
@Service
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final MarkdownSkillLoader loader = new MarkdownSkillLoader();
    private final ConcurrentHashMap<String, Skill> skills = new ConcurrentHashMap<>();

    public
    @NonNull
    SkillRegistry(@Value("${veto.skills.project-dir:}") String projectSkillsDir) {
        register(loadSkillsFrom(homeSkillsDir(), SkillSourceType.PERSONAL));
        if (projectSkillsDir != null && !projectSkillsDir.isBlank()) {
            register(loadSkillsFrom(Path.of(projectSkillsDir), SkillSourceType.PROJECT));
        }
        log.info("SkillRegistry: loaded {} skill(s).", skills.size());
    }

    /** Lookup by name. Used by persona resolution and the {@code load_skill} tool. */
    public @NonNull Optional<Skill> get(@NonNull String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /** Verify integrity hash. Returns empty if the file was tampered. */
    public @NonNull Optional<Skill> verifyAndLoad(
            @NonNull String name, @NonNull Path skillDirectory) {
        Skill skill = skills.get(name);
        if (skill == null) {
            return Optional.empty();
        }
        return loader.verifyIntegrity(skill) ? Optional.of(skill) : Optional.empty();
    }

    /**
     * For {@code load_skill}: lookup + verify integrity, returning the full body. Returns empty if
     * not found or tampered.
     */
    public @NonNull Optional<Skill> loadVerified(@NonNull String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            return Optional.empty();
        }
        if (!loader.verifyIntegrity(skill)) {
            log.warn("Skill '{}' failed integrity verification — rejecting.", name);
            return Optional.empty();
        }
        return Optional.of(skill);
    }

    /** Lightweight catalog of {@code (name, description)} pairs for the system prompt. */
    public List<Skill> catalog() {
        return new ArrayList<>(skills.values());
    }

    private void register(java.util.Map<String, Skill> loaded) {
        loaded.forEach((k, v) -> skills.merge(k, v, (a, b) -> a));
    }

    private java.util.Map<String, Skill> loadSkillsFrom(Path dir, SkillSourceType type) {
        return loader.loadSkillsFromDir(dir, type);
    }

    private static Path homeSkillsDir() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".veto", "skills");
    }
}
