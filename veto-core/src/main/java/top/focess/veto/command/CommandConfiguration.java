package top.focess.veto.command;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.command.commands.*;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.vault.*;

@Configuration
public class CommandConfiguration {

    private final ConcurrentHashMap<String, String> activePatterns = new ConcurrentHashMap<>();

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
            CredentialVaultConfiguration vaultConfig,
            PromptHandler promptHandler) {

        CommandRegistry registry = new CommandRegistry();
        registry.setPromptHandler(promptHandler);
        Path vaultHome = Path.of(vaultConfig.getVaultHome());

        Map<String, String> descs = new LinkedHashMap<>();

        registry.register(new LoginCommand(users, keys, vault));
        descs.put("login", "Sign in to your account");

        registry.register(new LogoutCommand(vault));
        descs.put("logout", "Sign out and lock the vault");

        registry.register(new SignupCommand(users, keys, vault));
        descs.put("signup", "Create a new account (first-run)");

        registry.register(new StatusCommand(vault, promptHandler));
        descs.put("status", "Show session info and usage stats");

        registry.register(new ExitCommand());
        descs.put("exit", "Quit the terminal");

        PatternCommand pc = new PatternCommand(vault, activePatterns, vaultHome);
        registry.register(pc);
        descs.put("pattern", "Manage agent patterns (provider, model, key)");

        registry.register(new HelpCommand(registry.all(), descs));
        return registry;
    }
}
