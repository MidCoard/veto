package top.focess.veto.agent.identity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Resolves the agent's base system prompt (Layer 1 of the {@code PromptCompiler} assembly). The
 * default is the shipped Veto agent template documented by {@code
 * plans/mvp-core/part5_agent/agent_identity_persona.md} and bundled as the classpath resource
 * {@code veto/default-system-prompt.md}.
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

    /** §4.1 default agent name. */
    public static final @NonNull String NAME = "VetoCoreAgent";

    /** §4.1 default agent description. */
    public static final @NonNull String DESCRIPTION =
            "General-purpose engineering assistant for workspace and code automation.";

    private final @NonNull String defaultPrompt;

    public SystemPromptResolver() {
        this.defaultPrompt = loadDefault();
    }

    /**
     * The bundled default system prompt (the Layer-1 base handed to the {@code PromptCompiler}).
     */
    public @NonNull String defaultPrompt() {
        return defaultPrompt;
    }

    private static @NonNull String loadDefault() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (text.isBlank()) {
                log.warn(
                        "Bundled default system prompt ({}) is blank - using minimal stub",
                        RESOURCE);
                return minimalStub();
            }
            return text;
        } catch (IOException e) {
            log.warn(
                    "Could not load bundled default system prompt ({}) - using minimal stub",
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
