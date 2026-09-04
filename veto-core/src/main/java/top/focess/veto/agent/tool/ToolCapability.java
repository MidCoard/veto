package top.focess.veto.agent.tool;

import java.util.Locale;
import org.jspecify.annotations.NonNull;

/**
 * The effect a tool can cause. Capability is independent from definition flavour and danger: it
 * selects the execution boundary and capability-specific authorization checks. Multiple tools
 * belong to one capability when they cross the same authority boundary; capabilities are not
 * intended to be one-per-tool.
 */
public enum ToolCapability {
    WORKSPACE_READ,
    WORKSPACE_WRITE,
    PROCESS_EXECUTION,
    TASK_CONTROL,
    NETWORK_EGRESS,
    SKILL_READ,
    MEMORY_READ,
    MEMORY_WRITE,
    LOOP_CONTROL,
    DELEGATION,
    GROUP_CONTROL,
    USER_INTERACTION,
    /** Fail-closed fallback for an agent tool that has not yet declared a specific capability. */
    AGENT_CONTROL,
    REMOTE_UNKNOWN;

    /**
     * Human-readable catalog heading derived from the enum identifier. Normalizing the display name
     * back to upper snake case always produces {@link #name()}, so the prompt cannot drift from the
     * capability manifest.
     */
    public @NonNull String displayName() {
        String[] words = name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder display = new StringBuilder();
        for (String word : words) {
            if (!display.isEmpty()) {
                display.append(' ');
            }
            display.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return display.toString();
    }
}
