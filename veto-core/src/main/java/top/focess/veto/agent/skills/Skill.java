package top.focess.veto.agent.skills;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * A Skill is a high-level agentic workflow — a sequence of tool-invocation guidelines loaded on
 * demand via the {@code load_skill} tool. (kept in sync with ).
 *
 * <h3>Integrity model</h3>
 *
 * Each skill carries a {@link #contentHash} (SHA-256 of the SKILL.md content), stored in the
 * database and protected from tampering. On every load the hash is recomputed and compared: NATIVE
 * hashes are pre-seeded at install time; PERSONAL/PROJECT use trust-on-first-use. A mismatch
 * rejects the skill.
 *
 * @param name skill name
 * @param description human-readable summary (advertised in the system prompt skill catalog)
 * @param promptInstructions markdown body from SKILL.md (loaded lazily via {@code load_skill})
 * @param sourceType where the skill lives
 * @param skillDirectory the skill's on-disk directory
 * @param requiredTools informational — tells the agent which tools a step needs; NOT validated
 *     against the agent's whitelist at load time
 * @param contentHash SHA-256, verified by the harness on every {@code load_skill} call
 */
public record Skill(
        @NonNull String name,
        @NonNull String description,
        String promptInstructions,
        SkillSourceType sourceType,
        Path skillDirectory,
        @NonNull List<String> requiredTools,
        @NonNull String contentHash) {}
