package top.focess.veto.command;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.SessionCompactor;
import top.focess.veto.command.commands.*;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.vault.*;

@Configuration
public class CommandConfiguration {

    // Shared state (was in TerminalChannel, now here for command access)
    private final ConcurrentHashMap<String, Agent> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionCompactor> compactors =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activePatterns = new ConcurrentHashMap<>();

    @Bean
    public CommandRegistry commandRegistry(
            CredentialVault vault,
            UserRegistry users,
            VaultKeyManager keys,
            UniformLLMCaller caller,
            CredentialVaultConfiguration vaultConfig) {

        CommandRegistry registry = new CommandRegistry();
        Path vaultHome = Path.of(vaultConfig.getVaultHome());

        // Order matters: earlier = higher in /help listing
        registry.register(new StatusCommand(vault));
        registry.register(new SendCommand(vault, caller, sessions, compactors, activePatterns));
        registry.register(new TurnsCommand(sessions));
        registry.register(new LoginCommand(users, keys, vault));
        registry.register(new LogoutCommand(vault));
        registry.register(new SignupCommand(users, keys, vault));
        registry.register(new ExitCommand());

        // Agent pattern sub-commands
        AgentPatternCommands apc = new AgentPatternCommands(vault, activePatterns, vaultHome);
        for (CommandHandler h : apc.all()) {
            registry.register(h);
        }

        // HelpCommand needs the full handler list — register it last
        List<CommandHandler> handlerList = new ArrayList<>(registry.handlers());
        registry.register(new HelpCommand(handlerList));

        return registry;
    }
}
