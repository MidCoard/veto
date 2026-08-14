package top.focess.veto.agent.intercept;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.workspace.Resolution;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;

/**
 * Extracts a {@link PermissionGrant.ToolCallSpec} from a {@link ToolCall} — the structural pieces
 * that grants match against. Pure (no side effects, no I/O).
 */
public final class MatchKeyExtractor {

    private MatchKeyExtractor() {}

    /**
     * Build a {@link PermissionGrant.ToolCallSpec} for the given call. The workspace is consulted
     * to canonicalize the path arg (if any). Flag shape is the <b>sorted set</b> of flag tokens
     * (e.g. {@code ["-m", "--amend"]}) — presence-only, value-positions wildcarded. For {@code
     * run_command} the flag shape is taken from the first command's args, skipping the executable
     * and positional subcommand(s).
     */
    public static PermissionGrant.@NonNull ToolCallSpec extract(
            @NonNull ToolCall call, ToolDefinition def, @NonNull Workspace workspace) {
        Map<@NonNull String, Object> args = call.args();
        Path canonical = canonicalPathArg(call, def, workspace);
        List<@NonNull String> flagShape = flagShape(call, def);
        return new PermissionGrant.ToolCallSpec(call.toolName(), args, flagShape, canonical);
    }

    /** Extract the canonicalized path argument (the first FILESYSTEM_PATH param, if any). */
    private static Path canonicalPathArg(
            @NonNull ToolCall call, ToolDefinition def, @NonNull Workspace workspace) {
        if (def == null) {
            return null;
        }
        Map<@NonNull String, @NonNull ParamCategory> hints = paramHints(def);
        if (hints.isEmpty()) {
            return null;
        }
        Map<@NonNull String, Object> args = call.args();
        for (var entry : hints.entrySet()) {
            if (entry.getValue() != ParamCategory.FILESYSTEM_PATH) {
                continue;
            }
            Object v = args.get(entry.getKey());
            if (!(v instanceof String s) || s.isBlank()) {
                continue;
            }
            try {
                Resolution res = workspace.pathResolver().resolveToHost(s);
                if (res.inScope() && res.hostPath() != null) {
                    return res.hostPath();
                }
                return Path.of(s).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                return Path.of(s).toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /**
     * Extract the flag shape — the sorted unique flag tokens (e.g. {@code ["-m", "--amend"]}). For
     * {@code run_command} the first command's args are scanned: a token is a flag if it starts with
     * {@code -}; positional subcommand + value-positions are skipped. For read/write tools, the
     * args map is scanned for boolean/flag-shaped values (present keys) — this is a coarse first
     * cut; a refined extractor can be plugged in later.
     */
    private static @NonNull List<@NonNull String> flagShape(
            @NonNull ToolCall call, ToolDefinition def) {
        if (def != null && def.risk() == RiskCategory.SHELL_EXEC) {
            return commandFlagShape(call);
        }
        // For native read/write: take the sorted set of args keys that look like flags.
        // Boolean / non-string keys count as flag-presence; their values are wildcarded.
        Map<@NonNull String, Object> args = call.args();
        TreeSet<@NonNull String> flags = new TreeSet<>();
        for (var entry : args.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (key.startsWith("-")) {
                flags.add(key);
            } else if (val instanceof Boolean) {
                // boolean arg → flag-presence
                flags.add("--" + key);
            }
        }
        return new ArrayList<>(flags);
    }

    @SuppressWarnings("unchecked")
    private static @NonNull List<@NonNull String> commandFlagShape(@NonNull ToolCall call) {
        Map<@NonNull String, Object> args = call.args();
        Object commandsObj = args.get("commands");
        if (!(commandsObj instanceof List<?> commands) || commands.isEmpty()) {
            return List.of();
        }
        Object first = commands.get(0);
        if (!(first instanceof Map<?, ?> cmd)) {
            return List.of();
        }
        Object argsObj = cmd.get("args");
        if (!(argsObj instanceof List<?> argList)) {
            return List.of();
        }
        TreeSet<@NonNull String> flags = new TreeSet<>();
        for (Object a : argList) {
            if (a instanceof String s && s.startsWith("-")) {
                flags.add(s);
            }
        }
        return new ArrayList<>(flags);
    }

    private static @NonNull Map<@NonNull String, @NonNull ParamCategory> paramHints(
            @NonNull ToolDefinition def) {
        if (def instanceof NativeToolDefinition n) {
            return n.paramHints();
        }
        if (def instanceof top.focess.veto.agent.mcp.AgentToolDefinition a) {
            return a.paramHints();
        }
        return Map.of();
    }
}
