package top.focess.veto.group;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.model.tier.ModelTier;

/**
 * A Mate's resolved tier + system-prompt base + session owner, produced by {@link GroupSpawner}
 * from the skillset config (or the global Mate defaults) and passed to {@link
 * GroupSpawner.AgentFactory#create}. The factory resolves the concrete provider/model/credential
 * from the tier via the {@link top.focess.veto.model.tier.ModelTierRegistry} for the owner's active
 * profile; the system-prompt base is role-specific and kept here (never on the persona).
 *
 * <p>The {@code owner} (the session owner username) is carried here because the factory runs on the
 * {@link GroupTickScheduler} thread when a Mate is lazily provisioned - outside any tool-call
 * scope, so the owner cannot be read from the {@link
 * top.focess.veto.agent.mcp.ToolCallContextHolder} thread-local there. It is stamped on the {@link
 * Group} at {@code create_group} time and flows here via {@link GroupSpawner#startMate}.
 *
 * @param tier the model tier this Mate runs on
 * @param systemPromptBase the Mate's system-prompt base (null/blank → persona-derived)
 * @param owner the session owner whose active model-tier profile resolves this Mate's tier
 *     (nullable when the group was created without an owner, e.g. in tests)
 */
public record MateBinding(
        @NonNull ModelTier tier, @Nullable String systemPromptBase, @Nullable String owner) {}
