package top.focess.veto.group;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.model.tier.ModelTier;

/**
 * A Mate's resolved tier + system-prompt base, produced by {@link GroupSpawner} from the skillset
 * config (or the global Mate defaults) and passed to {@link GroupSpawner.AgentFactory#create}. The
 * factory resolves the concrete provider/model/credential from the tier via the {@link
 * top.focess.veto.model.tier.ModelTierRegistry}; the system-prompt base is role-specific and kept
 * here (never on the persona).
 *
 * @param tier the model tier this Mate runs on
 * @param systemPromptBase the Mate's system-prompt base (null/blank -> persona-derived)
 */
public record MateBinding(@NonNull ModelTier tier, @Nullable String systemPromptBase) {}
