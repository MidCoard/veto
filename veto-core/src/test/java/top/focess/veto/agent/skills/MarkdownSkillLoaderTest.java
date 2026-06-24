package top.focess.veto.agent.skills;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Validates {@link MarkdownSkillLoader} parsing + SHA-256 integrity verification. */
class MarkdownSkillLoaderTest {

    private final MarkdownSkillLoader loader = new MarkdownSkillLoader();

    private static final String SKILL_MD =
            """
            ---
            name: verify_suite
            description: Compiles and tests the project to verify code integrity.
            tools:
              required:
                - name: run_gradle
                - name: grep_search
            ---
            To verify code integrity, execute these actions in order:
            1. Run `run_gradle` with args `tasks=spotlessApply` to format.
            2. Run `run_gradle` with args `tasks=compileJava` to compile.""";

    @Test
    void parsesFrontmatterAndBody(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("verify_suite");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), SKILL_MD);

        Optional<Skill> parsed =
                loader.parseSkillFile(skillDir.resolve("SKILL.md"), SkillSourceType.PERSONAL);
        assertTrue(parsed.isPresent());
        Skill skill = parsed.get();
        assertEquals("verify_suite", skill.name());
        assertEquals(
                "Compiles and tests the project to verify code integrity.", skill.description());
        assertTrue(skill.promptInstructions().contains("execute these actions in order"));
        assertEquals(SkillSourceType.PERSONAL, skill.sourceType());
        assertEquals(java.util.List.of("run_gradle", "grep_search"), skill.requiredTools());
        assertNotNull(skill.contentHash());
    }

    @Test
    void verifyIntegrityPassesForUnmodifiedFile(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("verify_suite");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), SKILL_MD);

        Skill skill =
                loader.parseSkillFile(skillDir.resolve("SKILL.md"), SkillSourceType.NATIVE).get();
        assertTrue(loader.verifyIntegrity(skill), "unmodified file verifies");
    }

    @Test
    void verifyIntegrityRejectsTamperedFile(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("verify_suite");
        Files.createDirectories(skillDir);
        Path file = skillDir.resolve("SKILL.md");
        Files.writeString(file, SKILL_MD);

        Skill skill = loader.parseSkillFile(file, SkillSourceType.NATIVE).get();
        // Tamper: append to the body (not the frontmatter)
        Files.writeString(file, SKILL_MD + "\nExtra malicious instruction.\n");
        assertFalse(loader.verifyIntegrity(skill), "tampered file is rejected");
    }

    @Test
    void loadSkillsFromDirIndexesByName(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("verify_suite");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), SKILL_MD);

        Map<String, Skill> loaded = loader.loadSkillsFromDir(tempDir, SkillSourceType.PERSONAL);
        assertEquals(1, loaded.size());
        assertTrue(loaded.containsKey("verify_suite"));
    }
}
