package top.focess.veto.agent.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * JPA persistence for a skill's integrity hash (Part 5 "skill DB hash storage"). The hash is the
 * tamper-evidence anchor — the body itself lives in the {@code SKILL.md} file in the personal /
 * project skill directory; the database only stores the hash + metadata needed to detect tampering
 * on load.
 *
 * <p>Unique key on {@code (name, sourceType)} — the same skill name may exist in multiple source
 * tiers (NATIVE, PERSONAL, PROJECT); the tier is part of the identity.
 */
@Entity
@Table(name = "skill_hashes")
public class SkillEntity {

    @Id private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "skill_directory")
    private String skillDirectory;

    @Column(name = "required_tools", columnDefinition = "TEXT")
    private String requiredTools;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    protected SkillEntity() {}

    public
    @NonNull
    SkillEntity(@NonNull Skill skill) {
        this.id = UUID.randomUUID().toString();
        this.name = skill.name();
        this.sourceType = skill.sourceType().name();
        this.contentHash = skill.contentHash();
        this.skillDirectory =
                skill.skillDirectory() == null ? null : skill.skillDirectory().toString();
        this.requiredTools =
                String.join(",", skill.requiredTools() == null ? List.of() : skill.requiredTools());
        this.description = skill.description();
    }

    public static @NonNull Skill toSkill(@NonNull SkillEntity e) {
        return new Skill(
                e.name,
                e.description == null ? "" : e.description,
                null, // body is loaded from disk on demand
                SkillSourceType.valueOf(e.sourceType),
                e.skillDirectory == null ? null : Path.of(e.skillDirectory),
                e.requiredTools == null || e.requiredTools.isBlank()
                        ? List.of()
                        : Arrays.asList(e.requiredTools.split(",")),
                e.contentHash);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(@NonNull String contentHash) {
        this.contentHash = contentHash;
    }

    public String getSkillDirectory() {
        return skillDirectory;
    }

    public String getRequiredTools() {
        return requiredTools;
    }

    public String getDescription() {
        return description;
    }
}
