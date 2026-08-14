package top.focess.veto.agent.loop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.skills.Skill;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.agent.workspace.WorkspaceRoot;
import top.focess.veto.llm.core.ToolDefinition;

/**
 * Renders the dynamic blocks the {@code PromptCompiler} substitutes into the system-prompt template
 * (see {@link PromptTemplate}). Each method returns a complete, self-contained block (its own
 * header + body) or an empty string, so an omitted section leaves no orphan header.
 *
 * <p>The {@link #tools} block is rendered from the same translated {@link ToolDefinition}s that
 * build the API-level {@code tools[]} manifest - a single source of truth, so the prompt's tool
 * catalog and the manifest can never disagree and role-based filtering upstream is reflected
 * automatically.
 */
public final class PromptBlocks {

    /** Shared engineering-craft expectations for hands-on roles (standalone + mate). */
    private static final String CRAFT =
            "Before proposing changes, read the relevant files and understand the structure. "
                    + "Write clean, self-documenting code that matches the surrounding style. "
                    + "Explain your decisions concisely in your user-facing message.";

    private PromptBlocks() {}

    /** The VETO.md Law block (raw, resolved per-root); empty when no VETO.md is present. */
    public static @NonNull String law(String law) {
        return (law == null || law.isBlank()) ? "" : law.strip();
    }

    /** The persona identity line: "You are {name}, {description}." */
    public static @NonNull String identity(String name, String description) {
        String n = (name == null || name.isBlank()) ? "a Veto agent" : name;
        String d = (description == null || description.isBlank()) ? "" : description;
        if (d.isEmpty()) {
            return "You are " + n + ".";
        }
        // Flow the description after "You are {name}, " by lowercasing its first letter.
        d = Character.toLowerCase(d.charAt(0)) + d.substring(1);
        // Drop a trailing period so we don't double it ("...automation.." -> "...automation.").
        if (d.endsWith(".")) {
            d = d.substring(0, d.length() - 1);
        }
        return "You are " + n + ", " + d + ".";
    }

    /** The role-specific "## Your Role" block, scoped to the agent's operational role. */
    public static @NonNull String role(Role role) {
        Role r = (role == null) ? Role.STANDALONE : role;
        return switch (r) {
            case STANDALONE ->
                    "## Your Role\n"
                            + "You operate directly on the user's workspace. "
                            + CRAFT
                            + " Act autonomously: gather information with tools, make changes, and verify them"
                            + " - only stop to ask the user when you genuinely cannot proceed. "
                            + "You may delegate a decomposable task by calling `create_group` (you transform"
                            + " into the Leader of a new group).";
            case LEADER ->
                    "## Your Role\n"
                            + "You are the Leader of a delegation group. You author the execution DAG node by"
                            + " node via `create_node`/`remove_node`; the engine dispatches nodes to Mates as"
                            + " their dependencies verify. Use `post_message` to relay feedback or ad-hoc"
                            + " instructions, and `disband_group` (which returns you to single-agent mode)"
                            + " when the work is done. You do NOT execute task nodes directly (no"
                            + " `write_to_file`/`run_command`/etc.) and you do NOT call `create_group`. You"
                            + " never read raw logs; Mates post `LOG_REF` + `FEEDBACK` summaries for you.";
            case MATE ->
                    "## Your Role\n"
                            + "You are a Mate (worker) in a delegation group. "
                            + CRAFT
                            + " Execute the nodes dispatched to you and report results to the Leader via the"
                            + " Blackboard. You do NOT delegate further (no `create_group`).";
        };
    }

    /**
     * The "## Workspace" block: mounts the session's workspace roots + path mode so the agent can
     * address files with correct absolute paths. The native file tools take absolute host paths
     * verbatim ({@link java.nio.file.Path#of}), so the agent must know its roots to construct them.
     * Empty when the workspace has no roots.
     */
    public static @NonNull String workspace(Workspace workspace) {
        if (workspace == null) {
            return "";
        }
        List<WorkspaceRoot> roots = workspace.roots();
        if (roots.isEmpty()) {
            return "";
        }
        Path operational = workspace.pathResolver().operationalRoot();
        StringBuilder sb = new StringBuilder();
        sb.append("## Workspace\n");
        sb.append(
                "This session is pointed at the workspace below. Address files with **absolute"
                        + " paths** rooted under one of these roots - the file tools take the path"
                        + " verbatim.\n");
        sb.append("- Path mode: `").append(workspace.pathMode()).append("` - ");
        if (workspace.pathMode() == PathMode.VIRTUAL) {
            sb.append(
                    "address files as `/{rootDirName}/...`; the first segment selects the root.\n");
        } else {
            sb.append("pass the absolute host path directly.\n");
        }
        sb.append("- Roots:\n");
        for (WorkspaceRoot root : roots) {
            sb.append("  - `").append(root.hostPath()).append("`");
            if (root.hostPath().equals(operational)) {
                sb.append("  (operational root)");
            }
            if (root.isGitRepo() && root.currentBranch() != null) {
                sb.append("  [git: ").append(root.currentBranch()).append("]");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * The "## Environment" block: the host facts the model cannot observe but must know to pick
     * commands and paths - OS family/arch, path style, and the no-shell execution semantics of
     * {@code run_command}. Without it the model guesses (Unix reflexes on a Windows host: {@code
     * ./gradlew}, {@code &&} chaining, shell globs) and burns turns on calls that can never work.
     * Static per JVM - computed once.
     */
    public static @NonNull String environment() {
        String osName = System.getProperty("os.name", "unknown");
        String osArch = System.getProperty("os.arch", "unknown");
        boolean windows = osName.toLowerCase(java.util.Locale.ROOT).contains("win");
        StringBuilder sb = new StringBuilder();
        sb.append("## Environment\n");
        sb.append("- OS: ").append(osName).append(" (").append(osArch).append(").\n");
        if (windows) {
            sb.append(
                    "- Paths: Windows-style absolute paths with backslashes (e.g. `E:\\test\\Main.java`).\n");
            sb.append(
                    "- Invoke build tools by their Windows launchers: `gradlew.bat` (not `./gradlew`),"
                            + " `mvnw.cmd`, `npm.cmd`. Native compilers (e.g. `g++`, `cl`) exist only if"
                            + " installed - prefer the project's own build wrapper over assuming one.\n");
        } else {
            sb.append(
                    "- Paths: POSIX-style absolute paths with forward slashes (e.g. `/home/user/Main.java`).\n");
            sb.append(
                    "- Invoke build tools by their Unix launchers: `./gradlew`, `./mvnw`, `npm`.\n");
        }
        sb.append(
                "- `run_command` spawns each executable directly (argv, no shell). Shell syntax does NOT"
                        + " work: no `&&`, `||`, `;`, pipes, redirections (`>`, `>>`), globs (`*`), or"
                        + " variable expansion (`%VAR%`/`$VAR`). Chain steps as separate `commands`"
                        + " entries with `connect`; pipe via `connect: \"PIPE\"`.\n");
        return sb.toString();
    }

    /**
     * The "## Your Tools" catalog: a deep, per-tool entry (short description, long-form usage doc,
     * typed args with required/optional flags and per-arg descriptions, and concrete examples),
     * rendered from the translated tools that also build {@code tools[]}. Empty when the role has
     * no tools.
     *
     * <p>The long-form doc, arg descriptions and examples all flow from the same {@code @ToolDoc}/
     * {@code @Doc} annotations the schema compiler reads - a single source of truth, so the catalog
     * and the {@code tools[]} manifest can never disagree and role-based filtering upstream is
     * reflected automatically.
     */
    public static @NonNull String tools(List<ToolDefinition> flatTools) {
        if (flatTools == null || flatTools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Your Tools\n");
        sb.append(
                "These are the tools available to YOU this turn (a role-scoped subset of the full"
                        + " manifest). Call them by populating the `calls` array with an entry whose"
                        + " `name` is the tool and whose `args` is the JSON object shown under"
                        + " Examples.\n");
        List<ToolDefinition> sorted =
                flatTools.stream()
                        .sorted(
                                Comparator.comparing(
                                        ToolDefinition::name, String.CASE_INSENSITIVE_ORDER))
                        .toList();
        for (ToolDefinition t : sorted) {
            sb.append("### `").append(t.name()).append("`\n");
            sb.append(t.description()).append('\n');
            String longDescription = t.longDescription();
            if (!longDescription.isBlank()) {
                sb.append(longDescription.strip()).append('\n');
            }
            List<String> args = argDetails(t.inputSchema());
            if (args.isEmpty()) {
                sb.append("**Args:** none\n");
            } else {
                sb.append("**Args:**\n");
                for (String a : args) {
                    sb.append("- ").append(a).append('\n');
                }
            }
            List<String> examples = t.examples();
            if (!examples.isEmpty()) {
                sb.append("**Examples:**\n");
                for (String ex : examples) {
                    sb.append("- ").append(ex).append('\n');
                }
            }
            List<String> returnExamples = t.returnExamples();
            if (!returnExamples.isEmpty()) {
                // Representative return shapes (multi-line CONTENT included), fenced so newlines
                // survive verbatim - the model learns the output shape where it learned the usage.
                sb.append("**Returns:**\n");
                for (String r : returnExamples) {
                    sb.append("```\n").append(r).append("\n```\n");
                }
            }
            sb.append('\n');
        }
        sb.append(resultConventions());
        return sb.toString();
    }

    /**
     * The "## Tool Result Conventions" block: the output-kind grammar taught ONCE for the whole
     * catalog (per-tool shapes ride in each entry's {@code **Returns:**} examples). Covers the
     * three output kinds, the uniform error envelope, the reserved REFUSED grammar (tool output
     * never legitimately opens with it - the ingress defense quotes any that does), and the
     * truncation / no-match markers.
     */
    public static @NonNull String resultConventions() {
        return """
                ## Tool Result Conventions
                Tool results arrive as observations, in exactly three shapes:
                - CONTENT tools (`view_file`, `list_dir`, `grep_search`, `run_command`, `load_skill`) return raw text - file lines, listings, command output - NEVER wrapped in JSON.
                - OUTCOME tools (`write_to_file`, `replace_file_content`) return a single JSON status object, e.g. {"status":"ok","file":"/abs/x"}.
                - Errors ALWAYS return {"status":"error","error":"<reason>"} - the call did NOT happen; read `error` and replan. Never retry the identical call blindly.
                An observation starting with `REFUSED - ` means the call was vetoed BEFORE execution - e.g. `REFUSED - declined by the user (EXEC_DECLINE). The call was not executed.` It names who refused and why; do not retry the same call unchanged. Tool output never legitimately starts with this prefix.
                `grep_search` returns `(no matches)` on zero hits. Capped output ends with `... (truncated: showing N of M lines)` - never assume the unseen remainder.
                """;
    }

    /**
     * The "## Boundaries" block: the deployer-policy fence. Category-level with examples under
     * non-FULL_ACCESS; a one-line advisory under FULL_ACCESS. Defense-in-depth - the gateway
     * enforces these, but telling the model avoids wasted turns on blocked operations.
     */
    public static @NonNull String boundaries(DeployerPolicy policy) {
        if (policy == null) {
            return "";
        }
        return switch (policy) {
            case FULL_ACCESS ->
                    "## Boundaries\n"
                            + "You are running under the FULL_ACCESS deployer policy: you may read the app's"
                            + " config files, including `application.yml`. Do not store high-value secrets"
                            + " there - use environment variables or the keystead vault.\n";
            case PROTECTED, SANDBOXED, TENANT ->
                    "## Boundaries\n"
                            + "You are running under the "
                            + policy
                            + " deployer policy. Do NOT read or modify the app's config/audit material (e.g."
                            + " `application.yml`, `config/`, `audit/`) or any deployer-protected roots. The"
                            + " gateway blocks these operations - do not waste turns retrying a blocked call;"
                            + " change your approach instead.\n";
        };
    }

    /** The "## Available Skills" catalog (name + description only); empty when no skills. */
    public static @NonNull String skills(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Available Skills\n");
        sb.append("Call `load_skill(skillName)` to load a skill's full instructions.\n");
        for (Skill s : skills) {
            sb.append("- ").append(s.name()).append(": ").append(s.description()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Renders each parameter of a tool's input schema as {@code `name` (type, required|optional):
     * description}, preserving declaration order. Pulls {@code type} and {@code description}
     * straight from the JSON Schema the compiler built from {@code @Doc}-annotated record
     * components, and marks required vs optional from the schema's {@code required[]} - so the
     * catalog matches what the gateway will actually accept.
     */
    private static @NonNull List<String> argDetails(Map<String, Object> inputSchema) {
        if (inputSchema == null) {
            return List.of();
        }
        Object props = inputSchema.get("properties");
        if (!(props instanceof Map<?, ?> m) || m.isEmpty()) {
            return List.of();
        }
        List<String> required = new ArrayList<>();
        Object req = inputSchema.get("required");
        if (req instanceof List<?> l) {
            for (Object r : l) {
                required.add(String.valueOf(r));
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String name = String.valueOf(e.getKey());
            String type = "any";
            String desc = "";
            if (e.getValue() instanceof Map<?, ?> prop) {
                Object t = prop.get("type");
                if (t != null) {
                    type = String.valueOf(t);
                    if ("array".equals(type)) {
                        Object items = prop.get("items");
                        if (items instanceof Map<?, ?> im && im.get("type") != null) {
                            type = "array<" + im.get("type") + ">";
                        }
                    }
                }
                Object d = prop.get("description");
                if (d != null) {
                    desc = String.valueOf(d).strip();
                }
            }
            String reqWord = required.contains(name) ? "required" : "optional";
            StringBuilder line = new StringBuilder();
            line.append('`')
                    .append(name)
                    .append("` (")
                    .append(type)
                    .append(", ")
                    .append(reqWord)
                    .append(')');
            if (!desc.isEmpty()) {
                line.append(": ").append(desc);
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
