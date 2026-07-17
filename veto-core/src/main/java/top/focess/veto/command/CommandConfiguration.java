package top.focess.veto.command;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.agent.AgentService;
import top.focess.veto.command.commands.*;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.*;

/**
 * Spring {@link Configuration} that wires the command-layer beans for the Veto backend.
 *
 * <p>Two singleton beans are produced:
 *
 * <ul>
 *   <li>{@link PromptHandler} — handles plain-text LLM prompts forwarded from the terminal.
 *   <li>{@link CommandRegistry} — registers all slash-commands and delegates plain-text prompts to
 *       the {@code PromptHandler}.
 * </ul>
 */
@Configuration
public class CommandConfiguration {

    /**
     * Creates the {@link PromptHandler} bean - the terminal transport facade that delegates loop
     * execution to {@link AgentService} and session/config resolution to {@link SessionService}.
     *
     * @param vault the credential vault used to look up the current logged-in user
     * @param agentService the shared agent service that owns loop execution + agent lifecycle
     * @param sessionService the session service that owns the active-session map + config
     *     resolution
     * @return the configured {@link PromptHandler} singleton
     */
    @Bean
    public @NonNull PromptHandler promptHandler(
            @NonNull CredentialVault vault,
            @NonNull AgentService agentService,
            @NonNull SessionService sessionService) {
        return new PromptHandler(vault, agentService, sessionService);
    }

    /**
     * Creates the {@link CommandRegistry} bean and registers all built-in slash-commands.
     *
     * <p>Registered commands (in order):
     *
     * <ol>
     *   <li>{@code /login} — authenticate with the credential vault
     *   <li>{@code /logout} — clear the active session
     *   <li>{@code /signup} — create a new user account
     *   <li>{@code /status} — display current session and agent state
     *   <li>{@code /exit} — terminate the terminal session
     *   <li>{@code /pattern} — manage LLM agent patterns
     *   <li>{@code /help} — list available commands
     * </ol>
     *
     * @param vault the credential vault used by auth-related commands
     * @param users the user registry for signup/login lookups
     * @param keys the key manager for credential creation and rotation
     * @param promptHandler the prompt handler bean; passed to logout/status commands so they can
     *     clear or inspect the agent session
     * @param patternRepo the pattern repository used by the pattern command
     * @return the fully-configured {@link CommandRegistry} singleton
     */
    @Bean
    public @NonNull CommandRegistry commandRegistry(
            @NonNull CredentialVault vault,
            @NonNull UserRegistry users,
            @NonNull PromptHandler promptHandler,
            @NonNull AgentPatternRepository patternRepo,
            @NonNull AuthLifecycleManager authLifecycleManager,
            @NonNull SessionService sessionService) {

        CommandRegistry registry = new CommandRegistry(promptHandler);

        registry.register(new LoginCommand(users, authLifecycleManager));
        registry.register(new LogoutCommand(authLifecycleManager, promptHandler));
        registry.register(new SignupCommand(users, authLifecycleManager));
        registry.register(new StatusCommand(vault, promptHandler));
        registry.register(new ExitCommand());
        registry.register(new PatternCommand(vault, patternRepo));
        registry.register(new SessionCommand(sessionService, promptHandler));
        registry.register(new CompactCommand(promptHandler));
        registry.register(new HelpCommand(registry));
        return registry;
    }
}
