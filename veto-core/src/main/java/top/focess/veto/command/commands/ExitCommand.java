package top.focess.veto.command.commands;

import top.focess.command.Command;
import top.focess.command.CommandResult;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;

public class ExitCommand extends Command {

    public ExitCommand() {
        super("exit", "quit");
        addExecutor(
                (sender, args, io) -> {
                    ((TerminalIO) io)
                            .respond(
                                    new TerminalResponse(
                                            ResponseType.MESSAGE,
                                            "Goodbye.",
                                            java.util.Map.of("exit", true)));
                    return CommandResult.ALLOW;
                });
    }

    @Override
    public void init() {
    }

    @Override
    public java.util.List<String> usage(top.focess.command.CommandSender s) {
        return java.util.List.of("/exit");
    }
}
