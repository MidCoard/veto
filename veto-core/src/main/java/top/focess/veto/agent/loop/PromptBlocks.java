package top.focess.veto.agent.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import top.focess.veto.llm.core.ToolResultPresentationMode;

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

    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    /** Shared engineering-craft expectations for hands-on roles (standalone + mate). */
    private static final String CRAFT =
            "Before proposing changes, read the relevant files and understand the structure. "
                    + "Write clean, self-documenting code that matches the surrounding style.";

    private PromptBlocks() {}

    /** The VETO.md Law block (resolved per-root); empty when no VETO.md is present. */
    public static @NonNull String law(String law) {
        return (law == null || law.isBlank())
                ? ""
                : "## Workspace Law\n"
                        + "The following VETO.md instructions apply to work in this workspace:\n\n"
                        + law.strip();
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
                            + "Role: STANDALONE. You operate directly on the user's workspace. "
                            + CRAFT
                            + " Explain your decisions concisely in the final response."
                            + " Act autonomously: gather information with tools, make changes, and verify them"
                            + " - only stop to ask the user when you genuinely cannot proceed. "
                            + "You may delegate a decomposable task by calling `create_group` (you transform"
                            + " into the Leader of a new group).";
            case LEADER ->
                    "## Your Role\n"
                            + "Role: LEADER. You author the execution DAG node by"
                            + " node via `create_node`/`remove_node`; the engine dispatches nodes to Mates as"
                            + " their dependencies verify. Use `inspect_group` to wait for and read Mate"
                            + " outcomes. Re-plan failed nodes with `remove_node`/`create_node`, and call"
                            + " `disband_group` (which returns you to single-agent mode) after the DAG is"
                            + " complete. You do NOT execute task nodes directly (no"
                            + " `write_to_file`/`run_command`/etc.) and you do NOT call `create_group`. You"
                            + " reason from the node states and Mate reports returned by `inspect_group`.";
            case MATE ->
                    "## Your Role\n"
                            + "Role: MATE. You are a worker in a delegation group. "
                            + CRAFT
                            + " Execute the assigned node and finish with a concise internal report. The"
                            + " engine captures that final message and delivers it to the Leader; you do not"
                            + " address the end user or post to the Blackboard yourself. You do NOT delegate"
                            + " further and cannot mutate the user's persistent memory.";
        };
    }

    /**
     * The "## Workspace" block: identifies the session's working context and path mode. Under
     * FULL_ACCESS with real host paths, roots are navigation/default-execution context rather than
     * an authorization boundary. Restrictive deployer policies continue to describe them as the
     * addressable scope. Empty when the workspace has no roots.
     */
    public static @NonNull String workspace(
            Workspace workspace, @NonNull DeployerPolicy deployerPolicy) {
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
        if (workspace.pathMode() == PathMode.VIRTUAL) {
            sb.append(
                    "This session uses virtual workspace paths. Only the mounted roots listed below"
                            + " are addressable through native file tools, regardless of deployer"
                            + " policy.\n");
        } else {
            switch (deployerPolicy) {
                case FULL_ACCESS ->
                        sb.append(
                                "This workspace is the default working context, not an access"
                                        + " boundary. You may use any absolute host path when the"
                                        + " user's task requires it; do not claim that an outside"
                                        + " path is blocked merely because it is outside these"
                                        + " roots.\n");
                case PROTECTED ->
                        sb.append(
                                "This workspace is the default working context, not the hard path"
                                        + " boundary. Absolute host paths outside these roots remain"
                                        + " addressable unless they match the deployer-owned"
                                        + " protected set; every target is still screened.\n");
                case SANDBOXED ->
                        sb.append(
                                "These session roots are a hard filesystem boundary within the"
                                        + " deployer-configured SANDBOXED zones. Do not access paths"
                                        + " outside them; canonical escapes are refused.\n");
                case TENANT ->
                        sb.append(
                                "These session roots are this user's hard filesystem boundary"
                                        + " within the deployer-configured TENANT zone. Do not access"
                                        + " outside roots or another user's unshared workspace;"
                                        + " canonical and cross-user escapes are refused.\n");
            }
        }
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
        return environment(true);
    }

    /** Render host facts, including command semantics only when this role can execute commands. */
    public static @NonNull String environment(boolean commandToolsAvailable) {
        String osName = System.getProperty("os.name", "unknown");
        String osArch = System.getProperty("os.arch", "unknown");
        boolean windows = osName.toLowerCase(java.util.Locale.ROOT).contains("win");
        StringBuilder sb = new StringBuilder();
        sb.append("## Environment\n");
        sb.append("- OS: ").append(osName).append(" (").append(osArch).append(").\n");
        if (windows) {
            sb.append(
                    "- Paths: Windows-style absolute paths with backslashes (e.g. `E:\\test\\Main.java`).\n");
            if (commandToolsAvailable) {
                sb.append(
                        "- Invoke build tools by their Windows launchers: `gradlew.bat` (not `./gradlew`),"
                                + " `mvnw.cmd`, `npm.cmd`. Native compilers (e.g. `g++`, `cl`) exist only if"
                                + " installed - prefer the project's own build wrapper over assuming one.\n");
            }
        } else {
            sb.append(
                    "- Paths: POSIX-style absolute paths with forward slashes (e.g. `/home/user/Main.java`).\n");
            if (commandToolsAvailable) {
                sb.append(
                        "- Invoke build tools by their Unix launchers: `./gradlew`, `./mvnw`, `npm`.\n");
            }
        }
        if (commandToolsAvailable) {
            sb.append(
                    "- `run_command` spawns each executable directly (argv, no shell). Shell syntax does NOT"
                            + " work: no `&&`, `||`, `;`, pipes, redirections (`>`, `>>`), globs (`*`), or"
                            + " variable expansion (`%VAR%`/`$VAR`). Chain steps as separate `commands`"
                            + " entries with `connect`; pipe via `connect: \"PIPE\"`.\n");
        }
        return sb.toString();
    }

    /**
     * The "## Your Tools" catalog: a compact per-tool contract (description, typed args, result
     * formats, usage guidance, essential behavior, one call example, result contract, one result
     * example, and errors), rendered from the translated tools that also build {@code tools[]}.
     * Empty when the role has no tools.
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
                "These are the tools available to YOU (a role-scoped subset of the full manifest)."
                        + " Call them by populating the `calls` array with an entry whose `tool_name`"
                        + " is the tool and whose `args` matches the schema below."
                        + " Schematic examples use `<workspace-root>`; replace it with an exact root"
                        + " from the Workspace block and obey the current path mode.\n");
        List<ToolDefinition> sorted =
                flatTools.stream()
                        .sorted(
                                Comparator.comparing(
                                        ToolDefinition::name, String.CASE_INSENSITIVE_ORDER))
                        .toList();
        for (int toolIndex = 0; toolIndex < sorted.size(); toolIndex++) {
            if (toolIndex > 0) {
                sb.append("---\n");
            }
            ToolDefinition t = sorted.get(toolIndex);
            sb.append("### `").append(t.name()).append("`\n");
            sb.append(t.description()).append('\n');
            List<String> args = argDetails(t.inputSchema());
            sb.append("#### Args\n");
            if (args.isEmpty()) {
                sb.append("Pass an empty JSON object: `{}`.\n");
            } else {
                for (String a : args) {
                    sb.append("- ").append(a).append('\n');
                }
            }
            sb.append("#### Result formats\n");
            t.resultFormats()
                    .forEach(
                            format ->
                                    sb.append("- `")
                                            .append(format.id())
                                            .append("`: ")
                                            .append(format.description())
                                            .append(".\n"));
            var documentation = t.documentation();
            appendSectionIfPresent(sb, "Behavior", documentation.behavior());
            appendSectionIfPresent(sb, "When to use", documentation.whenToUse());
            appendSectionIfPresent(sb, "When not to use", documentation.whenNotToUse());
            List<String> examples = t.examples();
            if (!examples.isEmpty()) {
                sb.append("#### Call examples\n");
                sb.append("```json\n")
                        .append(schematicExample(examples.getFirst()))
                        .append("\n```\n");
            }
            appendSectionIfPresent(sb, "Result contract", documentation.resultContract());
            List<String> returnExamples = t.returnExamples();
            if (!returnExamples.isEmpty()) {
                sb.append("#### Result examples\n");
                String result = schematicResult(returnExamples.getFirst());
                sb.append("```")
                        .append(resultFenceLanguage(result))
                        .append('\n')
                        .append(result)
                        .append("\n```\n");
            }
            appendSectionIfPresent(sb, "Errors and edge cases", documentation.errorsAndEdgeCases());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendSectionIfPresent(
            @NonNull StringBuilder sb, @NonNull String heading, @NonNull String body) {
        if (!body.isBlank()) {
            appendSection(sb, heading, body);
        }
    }

    private static void appendSection(
            @NonNull StringBuilder sb, @NonNull String heading, @NonNull String body) {
        sb.append("#### ").append(heading).append('\n');
        if (!body.isBlank()) {
            sb.append(body.strip()).append('\n');
        }
    }

    /**
     * The "## Tool Result Conventions" block: the output-kind grammar taught ONCE for the whole
     * catalog (per-tool shapes ride in each entry's illustrative returns). Makes the per-tool
     * return contract authoritative, then documents the common error/refusal and truncation markers
     * without pretending every native, agent, and remote tool shares one envelope.
     */
    public static @NonNull String resultConventions() {
        return resultConventions(ToolResultPresentationMode.BASIC);
    }

    public static @NonNull String resultConventions(
            @NonNull ToolResultPresentationMode presentationMode) {
        if (!presentationMode.detailed()) {
            return """
                    ## Tool Result Conventions
                    Tool results contain the tool-specific content directly. Read each tool's Result contract to interpret whether that content is JSON or plain text. Failure diagnostics are self-contained in the content; if a call failed, correct the cause and do not report the operation as completed.
                    A policy refusal means the call did not execute. Do not retry it unchanged. A truncation marker means content is missing; never assume the unseen remainder.
                    """;
        }
        return """
                ## Tool Result Conventions
                Every tool result content is a JSON object with `status`, `format`, `content`, and `errorCode`. The `content` field is the exact tool-specific result. Interpret it using `format` (`json`, `plaintext`, or `unknown`) and the tool's Result contract. A failed call carries a diagnostic in `content`, not a successful result body: read it, correct the cause, and do not report the operation as completed.
                A policy refusal means the call did not execute. Do not retry it unchanged. Transient failures such as timeouts may be retried only a limited number of times.
                A truncation marker means content is missing; never assume the unseen remainder.
                """;
    }

    private static @NonNull String schematicExample(@NonNull String example) {
        String rendered =
                example.replace("\"/abs", "\"<workspace-root>")
                        .replace("\"/workspace", "\"<workspace-root>");
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            String pathPrefix = "<workspace-root>/";
            int pathStart = rendered.indexOf(pathPrefix);
            while (pathStart >= 0) {
                int pathEnd = rendered.indexOf('"', pathStart);
                if (pathEnd < 0) {
                    break;
                }
                String windowsPath = rendered.substring(pathStart, pathEnd).replace("/", "\\\\");
                rendered =
                        rendered.substring(0, pathStart)
                                + windowsPath
                                + rendered.substring(pathEnd);
                pathStart = rendered.indexOf(pathPrefix, pathEnd);
            }
            rendered =
                    rendered.replace(
                            "\"executable\": \"gradle\"", "\"executable\": \"gradlew.bat\"");
            rendered = rendered.replace("\"executable\": \"npm\"", "\"executable\": \"npm.cmd\"");
        }
        return rendered;
    }

    /**
     * Keep result examples portable instead of teaching POSIX-only placeholder paths on Windows.
     */
    private static @NonNull String schematicResult(@NonNull String example) {
        return example.replaceAll("/(?:abs|workspace)(?:/[^\\s\\\",}\\]]+)*", "<absolute-path>");
    }

    private static @NonNull String resultFenceLanguage(@NonNull String example) {
        String stripped = example.stripLeading();
        if (!stripped.startsWith("{") && !stripped.startsWith("[")) {
            return "text";
        }
        try {
            MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(stripped);
            return "json";
        } catch (JsonProcessingException ignored) {
            return "text";
        }
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
                            + "You are running under FULL_ACCESS. The deployer intentionally chose unrestricted"
                            + " host-path reachability and is responsible for that choice. Workspace roots are"
                            + " context, not permission boundaries. Every call still passes Gateway relevance"
                            + " and danger screening, jailbreak defenses, auditing, and HITL; DANGEROUS actions"
                            + " require user authorization and CRITICAL policy violations remain refused. If"
                            + " unrestricted host-path reachability is not intended, the deployer should use"
                            + " PROTECTED.\n";
            case PROTECTED ->
                    "## Boundaries\n"
                            + "You are running under PROTECTED. Host paths remain generally reachable, but"
                            + " the deployer-owned protected set is a hard deny-list. A protected target is"
                            + " CRITICAL and cannot be approved; do not retry it. Non-protected calls still"
                            + " pass relevance/danger screening, jailbreak defenses, auditing, and HITL, and"
                            + " DANGEROUS actions require user authorization.\n";
            case SANDBOXED ->
                    "## Boundaries\n"
                            + "You are running under SANDBOXED. The deployer configured project zones, and"
                            + " the session workspace roots admitted within those zones are hard path"
                            + " boundaries. Canonical paths outside the session roots and protected-set"
                            + " targets are CRITICAL and refused. Calls inside the boundary still pass"
                            + " relevance/danger screening, jailbreak defenses, auditing, and HITL;"
                            + " DANGEROUS actions require user authorization.\n";
            case TENANT ->
                    "## Boundaries\n"
                            + "You are running under TENANT. The deployer configured tenant zones; this"
                            + " authenticated user's admitted workspace roots are hard path boundaries."
                            + " Outside-zone paths and another user's unshared workspace are CRITICAL and"
                            + " refused; only owner-issued sharing can authorize cross-user access. Calls"
                            + " within the tenant boundary still pass relevance/danger screening, jailbreak"
                            + " defenses, auditing, and HITL, and DANGEROUS actions require user"
                            + " authorization.\n";
        };
    }

    /** The "## Available Skills" catalog (name + description only). */
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
