package top.focess.veto.api.agent.tool;

import java.util.Set;
import org.jspecify.annotations.NonNull;

/** Tool-owned options for one successful result; never decoded from model arguments or output. */
public record ToolResultOptions(@NonNull Set<@NonNull String> bypassedProcessors) {
    /** Results pass through all processors unless the executing tool explicitly says otherwise. */
    public static final @NonNull ToolResultOptions DEFAULT = new ToolResultOptions(Set.of());

    /** Captures the exact processors that may skip this result. */
    public ToolResultOptions {
        bypassedProcessors = Set.copyOf(bypassedProcessors);
    }
}
