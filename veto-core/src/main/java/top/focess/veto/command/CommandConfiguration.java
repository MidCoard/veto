package top.focess.veto.command;

import java.util.concurrent.ConcurrentHashMap;
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
    public TerminalSessionManager terminalSessionManager() {
        return new TerminalSessionManager();
    }

    @Bean
    public PromptHandler promptHandler(CredentialVault vault, UniformLLMCaller caller) {
        return new PromptHandler(vault, caller);
    }

    @Bean
    public CommandRegistry commandRegistry(
            CredentialVault vault,
            UserRegistry users,
            VaultKeyManager keys,
            UniformLLMCaller caller,
            PromptHandler promptHandler,
            TerminalSessionManager terminalSessionManager,
            AgentPatternRepository patternRepo) {

        CommandRegistry registry = new CommandRegistry();
        registry.setPromptHandler(promptHandler);
        registry.setTerminalSessionManager(terminalSessionManager);

        registry.register(new LoginCommand(users, keys, vault, terminalSessionManager));
        registry.register(new LogoutCommand(vault, terminalSessionManager, promptHandler));
        registry.register(new SignupCommand(users, keys, vault, terminalSessionManager));
        registry.register(new StatusCommand(vault, promptHandler));
        registry.register(new ExitCommand());
        registry.register(new PatternCommand(vault, patternRepo, activePatterns));
        registry.register(new HelpCommand(registry.all()));
        return registry;
    }
}
