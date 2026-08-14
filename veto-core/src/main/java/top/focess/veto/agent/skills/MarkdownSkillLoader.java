package top.focess.veto.agent.skills;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.mcp.ToolDocs;

/**
 * Scans the filesystem for {@code SKILL.md} files, parses the YAML frontmatter boundary, reads the
 * metadata (including tool dependencies), and instantiates the {@link Skill} model. Transcribed
 * from.
 *
 * <p>The skill's {@code contentHash} (SHA-256 of the markdown body) is computed at load and
 * verified on every {@code load_skill} call. NATIVE hashes are pre-seeded at install time;
 * PERSONAL/PROJECT use trust-on-first-use.
 */
public class MarkdownSkillLoader {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.skills.MarkdownSkillLoader");

    private final @NonNull YAMLMapper yamlMapper = new YAMLMapper();

    /** Scans the base skills directory and parses all {@code SKILL.md} files. */
    public @NonNull Map<String, @NonNull Skill> loadSkillsFromDir(
            @NonNull Path skillsDir, @NonNull SkillSourceType sourceType) {
        Map<String, Skill> skillsMap = new HashMap<>();
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            return skillsMap;
        }
        try (Stream<Path> walk = Files.walk(skillsDir)) {
            walk.filter(
                            path -> {
                                Path fileName = path.getFileName();
                                return fileName != null && fileName.toString().equals("SKILL.md");
                            })
                    .map(path -> parseSkillFile(path, sourceType))
                    .flatMap(Optional::stream)
                    .forEach(skill -> skillsMap.put(skill.name(), skill));
        } catch (Exception e) {
            log.warn("Failed to scan skills directory: {}", skillsDir, e);
        }
        return skillsMap;
    }

    /** Splits YAML frontmatter from Markdown body and deserializes the Skill. */
    public @NonNull Optional<Skill> parseSkillFile(
            @NonNull Path path, @NonNull SkillSourceType sourceType) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String[] parts = content.split("(?m)^---$");
            if (parts.length < 3) {
                return Optional.empty();
            }
            String yamlContent = parts[1].trim();
            StringBuilder markdownBodyBuilder = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                markdownBodyBuilder.append(parts[i]);
            }
            String markdownBody = markdownBodyBuilder.toString().trim();

            SkillMetadata metadata =
                    yamlMapper.readValue(yamlContent, ToolDocs.nonNullClass(SkillMetadata.class));
            if (metadata == null) {
                return Optional.empty();
            }

            List<String> requiredTools = List.of();
            if (metadata.tools() != null && metadata.tools().required() != null) {
                requiredTools =
                        metadata.tools().required().stream().map(ToolRequirement::name).toList();
            }

            Path skillDirectory = path.toAbsolutePath().normalize().getParent();
            if (skillDirectory == null) {
                return Optional.empty();
            }
            Skill skill =
                    new Skill(
                            metadata.name(),
                            metadata.description(),
                            markdownBody,
                            sourceType,
                            skillDirectory,
                            requiredTools,
                            computeSha256(markdownBody));
            return Optional.of(skill);
        } catch (Exception e) {
            log.warn("Failed to parse skill file at {}", path, e);
            return Optional.empty();
        }
    }

    /** Recomputes the SHA-256 of the on-disk SKILL.md body and compares it to the stored hash. */
    public boolean verifyIntegrity(@NonNull Skill skill) {
        Path skillDirectory = skill.skillDirectory();
        if (skillDirectory == null) {
            return false;
        }
        Path skillFile = skillDirectory.resolve("SKILL.md");
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            String[] parts = content.split("(?m)^---$");
            if (parts.length < 3) {
                return false;
            }
            StringBuilder body = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                body.append(parts[i]);
            }
            String recomputed = computeSha256(body.toString().trim());
            return recomputed.equals(skill.contentHash());
        } catch (Exception e) {
            log.warn(
                    "Integrity verification failed for skill '{}' at {}",
                    skill.name(),
                    skillFile,
                    e);
            return false;
        }
    }

    private static @NonNull String computeSha256(@NonNull String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record ToolRequirement(@NonNull String name) {}

    private record ToolRequirements(
            List<ToolRequirement> required, List<ToolRequirement> recommended) {}

    private record SkillMetadata(
            @NonNull String name, @NonNull String description, ToolRequirements tools) {}
}
