package top.focess.veto.command;

import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.agent.AgentService;
import top.focess.veto.command.commands.*;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.model.AgentPatternRepository;
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
 *
 * <p>The {@code activePatterns} map tracks the per-user active LLM pattern name. It is shared
 * between {@link PromptHandler} (reads the active pattern) and {@code PatternCommand} (writes it),
 * both of which receive the same {@link ConcurrentHashMap} instance.
 */
@Configuration
public class CommandConfiguration {

    /**
     * Per-user active LLM pattern name. Key = username, value = pattern name. Shared between the
     * {@link PromptHandler} (reader) and {@code PatternCommand} (writer).
     */
    private final ConcurrentHashMap<String, String> activePatterns = new ConcurrentHashMap<>();

    /**
     * Creates the {@link PromptHandler} bean — the terminal transport facade that delegates loop
     * execution to {@link AgentService}.
     *
     * @param vault the credential vault used to look up the current logged-in user
     * @param agentService the shared agent service that owns loop execution + agent lifecycle
     * @param patternRepo repository for user-defined agent patterns
     * @return the configured {@link PromptHandler} singleton
     */
    @Bean
    @NotNull
    public PromptHandler promptHandler(
            @NotNull CredentialVault vault,
            @NotNull AgentService agentService,
            @NotNull AgentPatternRepository patternRepo) {
        return new PromptHandler(vault, agentService, activePatterns, patternRepo);
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
     * @param caller the LLM caller (passed through to commands that need it)
     * @param promptHandler the prompt handler bean; passed to logout/status commands so they can
     *     clear or inspect the agent session
     * @param patternRepo the pattern repository used by the pattern command
     * @return the fully-configured {@link CommandRegistry} singleton
     */
    @Bean
    @NotNull
    public CommandRegistry commandRegistry(
            @NotNull CredentialVault vault,
            @NotNull UserRegistry users,
            @NotNull VaultKeyManager keys,
            @NotNull UniformLLMCaller caller,
            @NotNull PromptHandler promptHandler,
            @NotNull AgentPatternRepository patternRepo,
            @NotNull AuthLifecycleManager authLifecycleManager) {

        CommandRegistry registry = new CommandRegistry(promptHandler);

        registry.register(new LoginCommand(users, keys, authLifecycleManager));
        registry.register(new LogoutCommand(authLifecycleManager, promptHandler));
        registry.register(new SignupCommand(users, keys, authLifecycleManager));
        registry.register(new StatusCommand(vault, promptHandler));
        registry.register(new ExitCommand());
        registry.register(new PatternCommand(vault, patternRepo));
        registry.register(new AgentCommand(patternRepo, promptHandler));
        registry.register(new HelpCommand(registry));
        return registry;
    }
}
