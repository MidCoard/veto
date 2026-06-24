package top.focess.veto.agent.skills;

/**
 * Scope of a skill — where it lives and who can use it. Transcribed from {@code
 * plans/mvp-core/part5_agent/mcp_tool_foundation.md} §6.5.
 */
public enum SkillSourceType {
    /** Shipped with Veto. Hash pre-seeded in DB at install time. Immutable. */
    NATIVE,
    /**
     * Scoped to a specific user ({@code ~/.veto/skills/<name>/}). Available to that user across all
     * their projects.
     */
    PERSONAL,
    /**
     * Scoped to a project ({@code <workspace>/.veto/skills/<name>/}). Available to all members of
     * that project.
     */
    PROJECT
}
