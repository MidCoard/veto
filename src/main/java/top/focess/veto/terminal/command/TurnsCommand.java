package top.focess.veto.terminal.command;

import java.util.List;

import top.focess.command.*;
import top.focess.veto.terminal.TerminalContext;

public class TurnsCommand extends Command {

    private final TerminalContext ctx;

    public TurnsCommand(TerminalContext ctx) {
        super("turns");
        this.ctx = ctx;
    }

    @Override
    public void init() {
        addExecutor(
                (s, d, io) -> {
                    if (ctx.currentAgent == null || ctx.currentAgent.turns().isEmpty()) {
                        io.output("No turns yet.");
                        return CommandResult.ALLOW;
                    }
                    for (var t : ctx.currentAgent.turns()) {
                        io.output(
                                "#"
                                        + t.turnNumber()
                                        + ": "
                                        + t.thought()
                                        .substring(0, Math.min(t.thought().length(), 200))
                                        + "...");
                    }
                    return CommandResult.ALLOW;
                });
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/turns");
    }
}
