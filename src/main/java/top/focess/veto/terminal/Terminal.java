package top.focess.veto.terminal;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.focess.command.Command;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.terminal.command.*;
import top.focess.veto.vault.CredentialVault;
import top.focess.veto.vault.UserRegistry;
import top.focess.veto.vault.VaultKeyManager;

/**
 * Interactive terminal REPL for Veto. Built on the FocessCommand framework. Enabled with {@code
 * veto.terminal.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "veto.terminal.enabled", havingValue = "true", matchIfMissing = false)
public class Terminal implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Terminal.class);

    private final TerminalContext ctx;
    private final TerminalIOHandler io;

    public Terminal(
            UserRegistry users,
            VaultKeyManager keys,
            CredentialVault vault,
            UniformLLMCaller caller) {
        this.ctx = new TerminalContext(users, keys, vault, caller);
        this.io = new TerminalIOHandler();
    }

    @Override
    public void run(ApplicationArguments args) {
        registerCommands();
        io.output("╔══════════════════════════════════╗");
        io.output("║  Veto Terminal  v1.0            ║");
        io.output("║  Type /help for commands        ║");
        io.output("╚══════════════════════════════════╝");

        try (var scanner = new Scanner(System.in)) {
            while (true) {
                String prompt =
                        ctx.sender.isAuthenticated()
                                ? "veto:" + ctx.sender.getUsername() + "> "
                                : "veto> ";
                System.out.print(prompt);
                System.out.flush();
                String line;
                try {
                    line = scanner.nextLine().trim();
                } catch (NoSuchElementException e) {
                    break;
                }
                if (line.isEmpty()) continue;
                if (line.equals("/exit") || line.equals("/quit")) break;
                if (line.equals("/help")) {
                    printHelp();
                    continue;
                }

                String[] parts = line.split("\\s+");
                String cmdName = parts[0].startsWith("/") ? parts[0].substring(1) : parts[0];
                Command cmd = Command.get(cmdName);
                if (cmd == null) {
                    io.output("Unknown: " + parts[0] + " (try /help)");
                    continue;
                }
                try {
                    cmd.execute(ctx.sender, parts, io);
                } catch (Exception e) {
                    io.output("Error: " + e.getMessage());
                    log.debug("Command failed", e);
                }
            }
        }
        io.output("Goodbye.");
    }

    private void registerCommands() {
        Command.register(new SignupCommand(ctx));
        Command.register(new LoginCommand(ctx));
        Command.register(new LogoutCommand(ctx));
        Command.register(new SendCommand(ctx));
        Command.register(new TurnsCommand(ctx));
        Command.register(new StatusCommand(ctx));
    }

    private void printHelp() {
        io.output(
                """
                        Commands:
                          /signup <user> <pass>   Create account (first run only)
                          /login  <user> <pass>   Authenticate and unlock vault
                          /logout                 Lock vault
                          /send   <message>       Send a prompt to the agent
                          /turns                  Show turn history
                          /status                 Show current state
                          /help                   This message
                          /exit                   Shutdown""");
    }
}
