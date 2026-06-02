package top.focess.veto.terminal.command;

import java.util.List;

import top.focess.command.*;
import top.focess.veto.terminal.TerminalContext;

public class StatusCommand extends Command {

    private final TerminalContext ctx;

    public StatusCommand(TerminalContext ctx) {
        super("status");
        this.ctx = ctx;
    }

    @Override
    public void init() {
        addExecutor(
                (s, d, io) -> {
                    io.output(
                            "User: "
                                    + (ctx.sender.isAuthenticated()
                                    ? ctx.sender.getUsername()
                                    : "(none)"));
                    io.output("Vault: " + (ctx.vault.isUnlocked() ? "unlocked" : "locked"));
                    io.output(
                            "Agent: "
                                    + (ctx.currentAgent != null
                                    ? ctx.currentAgent.name()
                                    + " ("
                                    + ctx.currentAgent.state()
                                    + ")"
                                    : "(none)"));
                    io.output(
                            "Turns: "
                                    + (ctx.currentAgent != null
                                    ? ctx.currentAgent.turns().size()
                                    : 0));
                    return CommandResult.ALLOW;
                });
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/status");
    }
}
