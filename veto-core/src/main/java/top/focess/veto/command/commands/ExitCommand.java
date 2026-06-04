package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;

import top.focess.command.CommandSender;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;

public class ExitCommand extends VetoCommand {

    public ExitCommand() {
        super("exit", "Quit the terminal", "quit");
        addExecutor(
                (sender, args, io) -> {
                    ((TerminalIO) io)
                            .respond(
                                    new TerminalResponse(
                                            ResponseType.MESSAGE,
                                            "Goodbye.",
                                            Map.of("exit", true)));
                    return allow();
                });
    }

    @Override
    public void init() {
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/exit");
    }
}
