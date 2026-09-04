package top.focess.veto.agent.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.skills.Skill;
import top.focess.veto.agent.tool.ToolCapability;
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
                            + " further. You may recall existing session memories and cross-session insights,"
                            + " but you cannot create, promote, delete, or otherwise mutate them.";
        };
    }

    /** The "## Workspace" block: gives the model usable paths without exposing internal modes. */
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
        if (workspace.pathMode() == PathMode.VIRTUAL) {
            sb.append(
                    "Native file tools can address only the mounted workspace roots below. Use an"
                            + " absolute workspace path beginning with a listed root name.\n");
        } else {
            sb.append(
                    "Use an absolute path for every file-tool path argument. The roots below are"
                            + " the working context; the Boundaries section states whether other"
                            + " paths are reachable.\n");
        }
        sb.append("- Roots:\n");
        for (WorkspaceRoot root : roots) {
            Path displayedPath = root.hostPath();
            if (workspace.pathMode() == PathMode.VIRTUAL) {
                Path name = root.hostPath().getFileName();
                displayedPath = Path.of("/" + (name == null ? "" : name));
            }
            sb.append("  - `").append(displayedPath).append("`");
            if (root.hostPath().equals(operational)) {
                sb.append("  (operational root)");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** The "## Environment" block: host facts needed to construct valid absolute paths. */
    public static @NonNull String environment() {
        String osName = System.getProperty("os.name", "unknown");
        String osArch = System.getProperty("os.arch", "unknown");
        boolean windows = osName.toLowerCase(java.util.Locale.ROOT).contains("win");
        StringBuilder sb = new StringBuilder();
        sb.append("## Environment\n");
        sb.append("- OS: ").append(osName).append(" (").append(osArch).append(").\n");
        if (windows) {
            sb.append("- Use Windows absolute-path syntax for file-tool arguments.\n");
        } else {
            sb.append("- Use POSIX absolute-path syntax for file-tool arguments.\n");
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
                        + " Tools are grouped by capability; a capability heading describes the"
                        + " shared authority boundary, not permission, danger, or execution order."
                        + " Schematic examples use `<workspace-root>`; replace it with an exact root"
                        + " from the Workspace block.\n");
        List<ToolDefinition> sorted =
                flatTools.stream()
                        .sorted(
                                (@NonNull ToolDefinition left, @NonNull ToolDefinition right) -> {
                                    int byCapability =
                                            Integer.compare(
                                                    left.capability().ordinal(),
                                                    right.capability().ordinal());
                                    return byCapability != 0
                                            ? byCapability
                                            : String.CASE_INSENSITIVE_ORDER.compare(
                                                    left.name(), right.name());
                                })
                        .toList();
        ToolCapability currentCapability = null;
        for (int toolIndex = 0; toolIndex < sorted.size(); toolIndex++) {
            ToolDefinition t = sorted.get(toolIndex);
            if (t.capability() != currentCapability) {
                if (toolIndex > 0) {
                    sb.append('\n');
                }
                currentCapability = t.capability();
                sb.append("### Tool capability: ")
                        .append(currentCapability.displayName())
                        .append('\n');
            } else {
                sb.append("---\n");
            }
            sb.append("#### Tool name: `").append(t.name()).append("`\n");
            sb.append(t.description()).append('\n');
            List<String> args = argDetails(t.inputSchema());
            sb.append("##### Args\n");
            if (args.isEmpty()) {
                sb.append("Pass an empty JSON object: `{}`.\n");
            } else {
                for (String a : args) {
                    sb.append("- ").append(a).append('\n');
                }
            }
            sb.append("##### Result formats\n");
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
                sb.append("##### Call examples\n");
                sb.append("```json\n")
                        .append(schematicExample(examples.getFirst()))
                        .append("\n```\n");
            }
            appendSectionIfPresent(sb, "Result contract", documentation.resultContract());
            List<String> returnExamples = t.returnExamples();
            if (!returnExamples.isEmpty()) {
                sb.append("##### Result examples\n");
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
        sb.append("##### ").append(heading).append('\n');
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
                    Each tool result is exactly the tool-specific content described by that tool's Result contract; no common object surrounds it. A result may therefore be JSON text or plain text. Failure, refusal, cancellation, and interruption diagnostics also arrive directly as content. Read the diagnostic, correct the cause when possible, and never report an operation as completed unless its result confirms success.
                    A policy refusal means the call did not execute, so do not retry it unchanged. A truncation marker means content is missing; never assume the unseen remainder.
                    """;
        }
        return """
                ## Tool Result Conventions
                Every tool result is a JSON object with exactly four fields:

                - `status` is one of `success`, `failure`, `refused`, `cancelled`, or `interrupted`. `success` means the operation completed. `failure` means validation or execution failed. `refused` means policy prevented execution. `cancelled` means the pending operation was cancelled. `interrupted` means an operation stopped before normal completion.
                - `format` is one of `json`, `plaintext`, or `unknown` and describes `content`. `json` means `content` is a string containing the tool-specific JSON value from its Result contract; parse that nested string before using its fields. `plaintext` means ordinary text. `unknown` means the tool did not declare an encoding, so inspect `content` without assuming that JSON-looking text is structured.
                - `content` is always a string. On `success`, it contains the tool-specific result. Otherwise it contains the failure, refusal, cancellation, or interruption diagnostic.
                - `errorCode` is either a stable machine-readable string or `null` when no code applies. Do not infer success from this field; use `status`.
                Never report a non-`success` operation as completed. Do not retry `refused` calls unchanged. Retry transient failures such as timeouts only a limited number of times.
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

    /** The "## Boundaries" block: effective rules without exposing deployer policy names. */
    public static @NonNull String boundaries(DeployerPolicy policy, @NonNull PathMode pathMode) {
        if (policy == null) {
            return "";
        }
        if (pathMode == PathMode.VIRTUAL) {
            return switch (policy) {
                case FULL_ACCESS ->
                        "## Boundaries\n"
                                + "Native file tools can address only the mounted workspace roots. Every"
                                + " call still passes Gateway screening, jailbreak defenses, auditing, and"
                                + " human-approval checks; high-risk actions require user authorization and"
                                + " prohibited actions remain refused.\n";
                case PROTECTED ->
                        "## Boundaries\n"
                                + "Native file tools can address only mounted roots, and protected targets"
                                + " remain a hard"
                                + " deny-list. Other calls still pass Gateway screening and human-approval"
                                + " checks.\n";
                case SANDBOXED ->
                        "## Boundaries\n"
                                + "Mounted session roots"
                                + " are the hard filesystem boundary; canonical escapes and protected targets"
                                + " are refused. Other calls still pass Gateway screening and human-approval"
                                + " checks.\n";
                case TENANT ->
                        "## Boundaries\n"
                                + "This user's mounted roots"
                                + " are the hard filesystem boundary; cross-user access requires an"
                                + " owner-issued share. Other calls still pass Gateway screening and"
                                + " human-approval checks.\n";
            };
        }
        return switch (policy) {
            case FULL_ACCESS ->
                    "## Boundaries\n"
                            + "Filesystem access is not limited to the listed workspace roots. They are the"
                            + " default working context, not an access boundary. You may use any absolute"
                            + " host path required by the task; do not claim that an outside path is blocked"
                            + " merely because it is outside these roots. Every call still passes Gateway relevance"
                            + " and risk screening, jailbreak defenses, auditing, and human-approval checks;"
                            + " high-risk actions require user authorization and prohibited actions remain"
                            + " refused.\n";
            case PROTECTED ->
                    "## Boundaries\n"
                            + "Host paths are generally reachable, but the protected set is a hard deny-list."
                            + " A protected target cannot be approved; do not retry it. Absolute host paths outside"
                            + " workspace roots remain addressable unless protected. Non-protected calls still"
                            + " pass relevance and risk screening, jailbreak defenses, auditing, and"
                            + " human-approval checks; high-risk actions require user authorization.\n";
            case SANDBOXED ->
                    "## Boundaries\n"
                            + "The session workspace roots are hard path"
                            + " boundaries. Canonical paths outside the session roots and protected-set"
                            + " targets are refused. Calls inside the boundary still pass relevance and risk"
                            + " screening, jailbreak defenses, auditing, and human-approval checks; high-risk"
                            + " actions require user authorization.\n";
            case TENANT ->
                    "## Boundaries\n"
                            + "This authenticated user's admitted workspace roots are hard path boundaries."
                            + " Outside-zone paths and another user's unshared workspace are refused; only"
                            + " owner-issued sharing can authorize cross-user access. Calls"
                            + " within the tenant boundary still pass relevance and risk screening, jailbreak"
                            + " defenses, auditing, and human-approval checks; high-risk actions require user"
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
        List<String> lines = new ArrayList<>();
        appendArgDetails(inputSchema, "", lines, 0);
        return lines;
    }

    private static void appendArgDetails(
            @NonNull Map<?, ?> schema,
            @NonNull String prefix,
            @NonNull List<String> lines,
            int depth) {
        if (depth > 4) {
            return;
        }
        Object props = schema.get("properties");
        if (!(props instanceof Map<?, ?> m) || m.isEmpty()) {
            return;
        }
        List<String> required = new ArrayList<>();
        Object req = schema.get("required");
        if (req instanceof List<?> l) {
            for (Object r : l) {
                required.add(String.valueOf(r));
            }
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String name = String.valueOf(e.getKey());
            String qualifiedName = prefix + name;
            String type = "any";
            String desc = "";
            Map<?, ?> nested = null;
            String nestedPrefix = qualifiedName + ".";
            if (e.getValue() instanceof Map<?, ?> prop) {
                Object t = prop.get("type");
                if (t != null) {
                    type = String.valueOf(t);
                    if ("array".equals(type)) {
                        Object items = prop.get("items");
                        if (items instanceof Map<?, ?> im && im.get("type") != null) {
                            type = "array<" + im.get("type") + ">";
                            if ("object".equals(String.valueOf(im.get("type")))) {
                                nested = im;
                                nestedPrefix = qualifiedName + "[].";
                            }
                        }
                    }
                }
                if ("object".equals(type) && prop.get("properties") instanceof Map<?, ?>) {
                    nested = prop;
                }
                Object d = prop.get("description");
                if (d != null) {
                    desc = String.valueOf(d).strip();
                }
            }
            String reqWord = required.contains(name) ? "required" : "optional";
            StringBuilder line = new StringBuilder();
            line.append('`')
                    .append(qualifiedName)
                    .append("` (")
                    .append(type)
                    .append(", ")
                    .append(reqWord)
                    .append(')');
            if (!desc.isEmpty()) {
                line.append(": ").append(desc);
            }
            lines.add(line.toString());
            if (nested != null) {
                appendArgDetails(nested, nestedPrefix, lines, depth + 1);
            }
        }
    }
}
