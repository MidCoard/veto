package top.focess.veto.agent.loop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.skills.Skill;
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
                            + "You may delegate a decomposable task by calling `create_group` (spawns a Leader"
                            + " + Mates).";
            case LEADER ->
                    "## Your Role\n"
                            + "You are the Leader of a delegation group. You plan (author the DAG), dispatch"
                            + " task nodes to Mates via `create_mate`/`dispatchTask`, relay feedback, and"
                            + " synthesize the final result. You do NOT execute task nodes directly (no"
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
            if (longDescription != null && !longDescription.isBlank()) {
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
            if (examples != null && !examples.isEmpty()) {
                sb.append("**Examples:**\n");
                for (String ex : examples) {
                    sb.append("- ").append(ex).append('\n');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
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
    private static List<String> argDetails(Map<String, Object> inputSchema) {
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
