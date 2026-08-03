package top.focess.veto.agent.intercept;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * A permission grant — the cached match key from an {@code accept_*_like_this} approval. Grants
 * convert future {@code ASK} calls into {@code APPROVE} (no prompt) for matching calls. They are:
 *
 * <ul>
 *   <li>Session-scoped (lifetime = the agent's session; cleared on terminate).
 *   <li>ASK-only (a grant never lets a CRITICAL call through; the deterministic floor re-runs
 *       path/shell classification regardless — secret/protected/out-of-scope paths refuse even with
 *       a grant, screening_model.md §7.2 #3).
 *   <li>Identical-match on the match key (not fuzzy) — structural, byte-exact on the matched
 *       positions.
 *   <li>Audited + revocable (the user can see what's been granted and clear them).
 * </ul>
 *
 * <p>Three flavours correspond to the three match-key shapes per screening_model.md §7.1:
 *
 * <ul>
 *   <li>{@link ReadGrant} — directory prefix (canonical) + read tool family + flag-shape.
 *   <li>{@link WriteGrant} — directory prefix (canonical) + tool.
 *   <li>{@link CommandGrant} — executable + leading subcommand(s) + flag presence/structure.
 * </ul>
 *
 * <p>A grant is created by the {@link HitlRegistry} when a user resolves an ASK prompt with a
 * {@code _LIKE_THIS} variant (e.g. {@link VetoOption#ACCEPT_READ_LIKE_THIS}). Match is computed by
 * {@link #matches(ToolCallSpec)}; equality + hashCode are not used for matching (identical-match is
 * structural, not record-equal).
 */
public sealed interface PermissionGrant
        permits PermissionGrant.ReadGrant,
                PermissionGrant.WriteGrant,
                PermissionGrant.CommandGrant,
                PermissionGrant.LegacySessionRule {

    /** Tool family this grant covers. */
    String toolFamily();

    /**
     * Returns true if the given call matches this grant's match key. The call's danger is checked
     * elsewhere (deterministic floor); this only confirms structural / canonical-path-prefix match.
     */
    boolean matches(ToolCallSpec call);

    /** A minimal spec of a call for matching — pulled from the {@code ToolCall} at match time. */
    record ToolCallSpec(
            String toolName,
            Map<String, Object> args,
            List<String> flagShape,
            Path canonicalPathArg) {}

    /**
     * Read grant: matches future read tools (view_file / list_dir / grep_search) whose canonical
     * file path is under {@link #directoryPrefix}. Flag-shape is a positional list of present flag
     * names (order is significant; presence-only, values wildcarded).
     */
    record ReadGrant(
            @NonNull String toolFamily,
            java.nio.file.@NonNull Path directoryPrefix,
            @NonNull List<String> flagShape)
            implements PermissionGrant {

        public ReadGrant {
            flagShape = List.copyOf(flagShape);
            directoryPrefix = directoryPrefix.toAbsolutePath().normalize();
        }

        @Override
        public boolean matches(@NonNull ToolCallSpec call) {
            if (!toolFamily.equals(call.toolName())
                    && !READ_TOOL_FAMILY.contains(call.toolName())) {
                return false;
            }
            if (call.canonicalPathArg() == null) {
                return false;
            }
            if (!call.canonicalPathArg().startsWith(directoryPrefix)) {
                return false;
            }
            return flagShape.equals(call.flagShape());
        }
    }

    /**
     * Write grant: matches future write tools whose canonical file path is under {@link
     * #directoryPrefix}.
     */
    record WriteGrant(
            @NonNull String toolName,
            java.nio.file.@NonNull Path directoryPrefix,
            @NonNull List<String> flagShape)
            implements PermissionGrant {

        public WriteGrant {
            flagShape = List.copyOf(flagShape);
            directoryPrefix = directoryPrefix.toAbsolutePath().normalize();
        }

        @Override
        public String toolFamily() {
            return toolName;
        }

        @Override
        public boolean matches(@NonNull ToolCallSpec call) {
            if (!toolName.equals(call.toolName())) {
                return false;
            }
            if (call.canonicalPathArg() == null) {
                return false;
            }
            return call.canonicalPathArg().startsWith(directoryPrefix)
                    && flagShape.equals(call.flagShape());
        }
    }

    /**
     * Command grant: matches future {@code run_command} calls by <b>executable + leading
     * subcommand(s) + flag presence/structure</b> (screening_model.md §7.1). Value positions (e.g.
     * the {@code -m} message, a branch name) are wildcarded — any value accepted. Paths in args are
     * never wildcarded — re-screened every call (this happens at the danger floor; matching here
     * confirms the structural shape only).
     */
    record CommandGrant(
            @NonNull String executable,
            @NonNull List<String> subcommands,
            @NonNull List<String> flagShape)
            implements PermissionGrant {

        public CommandGrant {
            subcommands = List.copyOf(subcommands);
            flagShape = List.copyOf(flagShape);
        }

        @Override
        public String toolFamily() {
            return "run_command";
        }

        @Override
        public boolean matches(@NonNull ToolCallSpec call) {
            if (!"run_command".equals(call.toolName())) {
                return false;
            }
            // Extract executable + subcommands from the call's commands[].
            List<String> callCmd = extractCommand(call);
            if (callCmd == null || callCmd.isEmpty()) {
                return false;
            }
            if (!executable.equals(callCmd.get(0))) {
                return false;
            }
            // The leading subcommands must match exactly.
            if (subcommands.size() > callCmd.size() - 1) {
                return false;
            }
            for (int i = 0; i < subcommands.size(); i++) {
                if (!subcommands.get(i).equals(callCmd.get(i + 1))) {
                    return false;
                }
            }
            // Flag shape must match.
            return flagShape.equals(call.flagShape());
        }

        @SuppressWarnings("unchecked")
        private static List<String> extractCommand(ToolCallSpec call) {
            Object commandsObj = call.args() == null ? null : call.args().get("commands");
            if (!(commandsObj instanceof List<?> commands) || commands.isEmpty()) {
                return null;
            }
            Object first = commands.get(0);
            if (!(first instanceof Map<?, ?> cmd)) {
                return null;
            }
            Object execObj = cmd.get("executable");
            Object argsObj = cmd.get("args");
            if (execObj == null) {
                return null;
            }
            java.util.List<String> out = new java.util.ArrayList<>();
            out.add(execObj.toString());
            if (argsObj instanceof List<?> argList) {
                for (Object a : argList) {
                    if (a != null) {
                        out.add(a.toString());
                    }
                }
            }
            return out;
        }
    }

    /** Read tool family — covered by a single ReadGrant. */
    java.util.Set<String> READ_TOOL_FAMILY =
            java.util.Set.of("view_file", "list_dir", "grep_search");

    /**
     * Legacy session rule (back-compat): identical-match on {@code (toolName, args)} for callers
     * that still use the old {@code ACCEPT_AS_SESSION_RULE} flow with a non-{@code run_command}
     * tool. Distinct from {@link ReadGrant}/{@link WriteGrant}/{@link CommandGrant} (no per-tool
     * shape) — kept so the old code path still works until callers migrate.
     */
    record LegacySessionRule(@NonNull String toolName, java.util.@NonNull Map<String, Object> args)
            implements PermissionGrant {

        @Override
        public String toolFamily() {
            return toolName;
        }

        @Override
        public boolean matches(@NonNull ToolCallSpec call) {
            return toolName.equals(call.toolName()) && args.equals(call.args());
        }
    }
}
