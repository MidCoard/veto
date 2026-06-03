package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;

import top.focess.veto.command.ArgDef;
import top.focess.veto.command.CommandHandler;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;

public class ExitCommand implements CommandHandler {

    @Override
    public String name() {
        return "exit";
    }

    @Override
    public String description() {
        return "Quit the terminal";
    }

    @Override
    public String usage() {
        return "exit";
    }

    @Override
    public List<ArgDef> arguments() {
        return List.of();
    }

    @Override
    public TerminalResponse execute(Map<String, Object> args, String sessionToken) {
        return new TerminalResponse(ResponseType.MESSAGE, "Goodbye.", Map.of("exit", true));
    }
}
