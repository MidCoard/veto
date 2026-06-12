package top.focess.veto.command;

import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.command.commands.*;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.vault.*;

@Configuration
public class CommandConfiguration {

    private final ConcurrentHashMap<String, String> activePatterns = new ConcurrentHashMap<>();

    @Bean
    @NotNull
    public PromptHandler promptHandler(
            @NotNull CredentialVault vault,
            @NotNull UniformLLMCaller caller,
            @NotNull AgentPatternRepository patternRepo) {
        return new PromptHandler(vault, caller, activePatterns, patternRepo);
    }

    @Bean
    @NotNull
    public CommandRegistry commandRegistry(
            @NotNull CredentialVault vault,
            @NotNull UserRegistry users,
            @NotNull VaultKeyManager keys,
            @NotNull UniformLLMCaller caller,
            @NotNull PromptHandler promptHandler,
            @NotNull AgentPatternRepository patternRepo) {

        CommandRegistry registry = new CommandRegistry(promptHandler);

        registry.register(new LoginCommand(users, keys, vault));
        registry.register(new LogoutCommand(vault, promptHandler));
        registry.register(new SignupCommand(users, keys, vault));
        registry.register(new StatusCommand(vault, promptHandler));
        registry.register(new ExitCommand());
        registry.register(new PatternCommand(vault, patternRepo, activePatterns));
        registry.register(new HelpCommand(registry));
        return registry;
    }
}
