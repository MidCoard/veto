package top.focess.veto.agent.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillRegistryTest {

    @Test
    void projectSkillOverridesPersonalSkillWithTheSameName(@TempDir @NonNull Path tempDir)
            throws Exception {
        Path personal = tempDir.resolve("personal");
        Path project = tempDir.resolve("project");
        writeSkill(personal, "review", "Personal review", "Use the personal workflow.");
        writeSkill(project, "review", "Project review", "Use the project workflow.");

        Skill selected = new SkillRegistry(personal, project).get("review").orElseThrow();

        assertEquals(SkillSourceType.PROJECT, selected.sourceType());
        assertEquals("Project review", selected.description());
    }

    @Test
    void personalSkillRemainsAvailableWithoutAProjectOverride(@TempDir @NonNull Path tempDir)
            throws Exception {
        Path personal = tempDir.resolve("personal");
        Path project = tempDir.resolve("project");
        writeSkill(personal, "review", "Personal review", "Use the personal workflow.");
        Files.createDirectories(project);

        Skill selected = new SkillRegistry(personal, project).get("review").orElseThrow();

        assertEquals(SkillSourceType.PERSONAL, selected.sourceType());
        assertEquals("Personal review", selected.description());
    }

    @Test
    void tamperedProjectOverrideIsRejectedWithoutFallingBackToPersonal(
            @TempDir @NonNull Path tempDir) throws Exception {
        Path personal = tempDir.resolve("personal");
        Path project = tempDir.resolve("project");
        writeSkill(personal, "review", "Personal review", "Use the personal workflow.");
        Path projectFile =
                writeSkill(project, "review", "Project review", "Use the project workflow.");
        SkillRegistry registry = new SkillRegistry(personal, project);
        Files.writeString(projectFile, Files.readString(projectFile) + "\nTampered.\n");

        assertTrue(registry.loadVerified("review").isEmpty());
        assertEquals(SkillSourceType.PROJECT, registry.get("review").orElseThrow().sourceType());
    }

    private static @NonNull Path writeSkill(
            @NonNull Path root,
            @NonNull String name,
            @NonNull String description,
            @NonNull String body)
            throws Exception {
        Path directory = root.resolve(name);
        Files.createDirectories(directory);
        Path file = directory.resolve("SKILL.md");
        Files.writeString(
                file,
                """
                ---
                name: %s
                description: %s
                tools:
                  required: []
                ---
                %s
                """
                        .formatted(name, description, body));
        return file;
    }
}
