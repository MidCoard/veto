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
 * default is the shipped Veto agent persona - {@code
 * plans/mvp-core/part5_agent/agent_identity_persona.md} §3.1 (ReAct-mode template) + §4.1 (shipped
 * defaults) - bundled as the classpath resource {@code veto/default-system-prompt.md}.
 *
 * <p>Per the design (§2.2), on first startup the default is bootstrapped into {@code
 * ~/.veto/users/{veto_user_id}/system-prompt.md} and the Veto user's edited version is read
 * verbatim thereafter (never overwritten, never set via REST, never persisted). That
 * bootstrap/edit-source path is a follow-up; this resolver currently returns the bundled default,
 * which already makes the designed prompt reach the model. Editing the bundled resource and
 * restarting is enough to polish it without a recompile.
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
                + "\nRespond in the veto_pulse JSON schema.";
    }
}
