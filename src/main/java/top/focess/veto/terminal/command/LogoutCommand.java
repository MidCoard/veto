package top.focess.veto.terminal.command;

import java.util.List;

import top.focess.command.*;
import top.focess.veto.terminal.TerminalContext;

public class LogoutCommand extends Command {

    private final TerminalContext ctx;

    public LogoutCommand(TerminalContext ctx) {
        super("logout");
        this.ctx = ctx;
    }

    @Override
    public void init() {
        addExecutor(
                (s, d, io) -> {
                    if (!ctx.sender.isAuthenticated()) {
                        io.output("Not logged in.");
                        return CommandResult.ALLOW;
                    }
                    ctx.vault.lock();
                    ctx.sender.deauthenticate();
                    ctx.currentAgent = null;
                    io.output("Logged out. Vault locked.");
                    return CommandResult.ALLOW;
                });
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/logout");
    }
}
