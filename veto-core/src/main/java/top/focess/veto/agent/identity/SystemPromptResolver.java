package top.focess.veto.agent.identity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Resolves the agent's base system prompt (Layer 1 of the {@code PromptCompiler} assembly) from the
 * bundled classpath resource {@code veto/default-system-prompt.md}.
 *
 * <p>The current implementation returns the bundled default. Per-user prompt-file bootstrapping and
 * editing are not implemented and must not be presented as active behavior. Because the resource is
 * loaded once when this component is constructed, a backend restart is required after changing the
 * bundled prompt in a built application.
 */
@Component
public class SystemPromptResolver {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.identity.SystemPromptResolver");

    private static final @NonNull String RESOURCE = "veto/default-system-prompt.md";

    /** Default agent name. */
    public static final @NonNull String NAME = "VetoCoreAgent";

    /** Default agent description. */
    public static final @NonNull String DESCRIPTION =
            "General-purpose engineering assistant for workspace and code automation.";

    private final @NonNull String defaultPrompt;
    private final @NonNull String guidedPrompt;
    private final @NonNull String delegationPrompt;
    private final @NonNull String answerStyle = loadRules("veto/answer-style.md");
    private final @NonNull Map<String, String> commonBlocks =
            Map.of(
                    "ANSWER_STYLE", answerStyle,
                    "PRESENTATION_CAPABILITIES", loadRules("veto/presentation-mermaid.md"),
                    "OPERATING_CONTRACT", loadRules("veto/operating-contract.md"),
                    "EXTERNAL_SOURCES", loadRules("veto/external-sources.md"),
                    "INSTRUCTION_PROVENANCE", loadRules("veto/instruction-provenance.md"),
                    "TOOL_CALLS", loadRules("veto/tool-calls.md"),
                    "RESPONSE_PROTOCOL", loadRules("veto/response-protocol.md"));

    /** Shared instructions; the template owns their order, not individual agent roles. */
    public @NonNull Map<String, String> commonBlocks() {
        return commonBlocks;
    }

    public @NonNull String answerStyle() {
        return answerStyle;
    }

    public SystemPromptResolver() {
        this.defaultPrompt = loadDefault();
        this.guidedPrompt = loadRules("veto/guided-system-prompt.md");
        this.delegationPrompt = loadRules("veto/delegation-system-prompt.md");
    }

    /**
     * The bundled default system prompt (the Layer-1 base handed to the {@code PromptCompiler}).
     */
    public @NonNull String defaultPrompt() {
        return defaultPrompt;
    }

    /** Rules and examples included only when the session enables guided execution. */
    public @NonNull String guidedPrompt() {
        return guidedPrompt;
    }

    /** Selection rules and examples included only when create_group is available. */
    public @NonNull String delegationPrompt() {
        return delegationPrompt;
    }

    /** Loads required instructions from a bundled classpath resource. */
    public static @NonNull String loadRules(@NonNull String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load prompt instructions: " + resource, e);
        }
    }

    private static @NonNull String loadDefault() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (text.isBlank()) {
                log.warn(
                        "Bundled default system prompt ({}) is blank - using fallback prompt",
                        RESOURCE);
                return minimalStub();
            }
            return text;
        } catch (IOException e) {
            log.warn(
                    "Could not load bundled default system prompt ({}) - using fallback prompt",
                    RESOURCE,
                    e);
            return minimalStub();
        }
    }

    private static @NonNull String minimalStub() {
        return "You are "
                + NAME
                + ", "
                + Character.toLowerCase(DESCRIPTION.charAt(0))
                + DESCRIPTION.substring(1)
                + "\nRespond with valid JSON matching the supplied response schema.";
    }
}
