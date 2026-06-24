package top.focess.veto.agent.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.McpEngine;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.skills.Skill;
import top.focess.veto.agent.skills.SkillRegistry;

/**
 * Resolves an agent's identity + capability manifest at session start. {@code
 * plans/mvp-core/part5_agent/agent_identity_persona.md} (name→object resolution) and (system prompt
 * location &amp; bootstrapping).
 *
 * <p><b>name→object resolution.</b> The persisted whitelisted tool names + skill names are resolved
 * into full domain objects: tool names via {@link McpEngine#resolveDefinition}; skill names via
 * {@link SkillRegistry}. Unresolvable names are dropped and logged (the agent runs without them
 * rather than failing the session). Agent tools are always-on and runtime-excluded from the
 * resolved whitelist.
 *
 * <p><b>system prompt.</b> The base system prompt is read from {@code ~/.veto/} (e.g. {@code
 * ~/.veto/system-prompt.md} for the default agent, {@code ~/.veto/agents/<name>.md} for a named
 * pattern). On first startup, if the file does not exist, it is bootstrapped from the bundled
 * default template and written; the deployer may then edit it freely — subsequent startups read the
 * deployer's version verbatim and never overwrite it. It is never set via REST and never persisted
 * to the database.
 */
@Service
public class PersonaResolver {

    private static final Logger log = LoggerFactory.getLogger(PersonaResolver.class);

    private final McpEngine mcpEngine;
    private final SkillRegistry skillRegistry;

    public PersonaResolver(McpEngine mcpEngine, SkillRegistry skillRegistry) {
        this.mcpEngine = mcpEngine;
        this.skillRegistry = skillRegistry;
    }

    /** The shipped default agent id/name. */
    public static final String DEFAULT_AGENT_ID = "VetoCoreAgent";

    public static final String DEFAULT_AGENT_DESCRIPTION =
            "General-purpose engineering assistant for workspace and code automation.";

    /** Bundled default system prompt template , written to {@code ~/.veto/} on first start. */
    static final String DEFAULT_SYSTEM_PROMPT =
            """
            You are a highly efficient software engineering assistant. Your goal is to help the user \
            inspect, modify, and build files in their workspace.

            When answering questions or performing tasks:
            1. Analyze the workspace structure and read relevant files before proposing modifications.
            2. Write clean, self-documenting code following best practices for the language in use.
            3. Explain your decisions clearly and concisely to the user in your output message.""";

    /**
     * Resolves the persisted names into a full {@link AgentPersona}. Unresolvable tools/skills are
     * dropped and logged.
     */
    public AgentPersona resolve(
            String id,
            String name,
            String description,
            Set<String> whitelistedToolNames,
            List<String> skillNames) {
        Set<ToolDefinition> tools = new LinkedHashSet<>();
        if (whitelistedToolNames != null) {
            for (String toolName : whitelistedToolNames) {
                ToolDefinition def = mcpEngine.resolveDefinition(toolName);
                if (def == null) {
                    log.warn("Persona '{}': tool '{}' unresolvable — dropped.", name, toolName);
                } else if (def instanceof AgentToolDefinition) {
                    // Agent tools are always-on and runtime-excluded from the persona whitelist.
                    log.debug(
                            "Persona '{}': agent tool '{}' not stored in whitelist.",
                            name,
                            toolName);
                } else {
                    tools.add(def);
                }
            }
        }
        List<Skill> skills = new ArrayList<>();
        if (skillNames != null) {
            for (String skillName : skillNames) {
                skillRegistry
                        .get(skillName)
                        .ifPresentOrElse(
                                skills::add,
                                () ->
                                        log.warn(
                                                "Persona '{}': skill '{}' unresolvable — dropped.",
                                                name,
                                                skillName));
            }
        }
        return new AgentPersona(id, name, description, tools, skills);
    }

    /** Convenience: the shipped default VetoCoreAgent persona with empty tool/skill sets. */
    public AgentPersona defaultPersona() {
        return resolve(
                DEFAULT_AGENT_ID, DEFAULT_AGENT_ID, DEFAULT_AGENT_DESCRIPTION, Set.of(), List.of());
    }

    /**
     * Resolves the base system prompt for an agent from {@code ~/.veto/}, bootstrapping it from the
     * bundled default template on first startup.
     *
     * @param agentName the agent name, or {@code null} for the default agent
     */
    public String resolveSystemPrompt(String agentName) {
        Path file = systemPromptFile(agentName);
        if (Files.exists(file)) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Failed to read system prompt '{}'; using default.", file, e);
                return DEFAULT_SYSTEM_PROMPT;
            }
        }
        return bootstrap(file);
    }

    private String bootstrap(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, DEFAULT_SYSTEM_PROMPT, StandardCharsets.UTF_8);
            log.info("Bootstrapped system prompt at {} from bundled default.", file);
            return DEFAULT_SYSTEM_PROMPT;
        } catch (IOException e) {
            log.warn(
                    "Failed to bootstrap system prompt at '{}'; using in-memory default.", file, e);
            return DEFAULT_SYSTEM_PROMPT;
        }
    }

    private static Path systemPromptFile(String agentName) {
        String home = System.getProperty("user.home");
        if (agentName == null || agentName.isBlank() || DEFAULT_AGENT_ID.equals(agentName)) {
            return Path.of(home, ".veto", "system-prompt.md");
        }
        return Path.of(home, ".veto", "agents", agentName + ".md");
    }
}
