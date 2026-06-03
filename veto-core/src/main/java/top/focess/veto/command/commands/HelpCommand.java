package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;

import top.focess.veto.command.ArgDef;
import top.focess.veto.command.CommandHandler;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;

public class HelpCommand implements CommandHandler {

    private final List<CommandHandler> allHandlers;

    public HelpCommand(List<CommandHandler> allHandlers) {
        this.allHandlers = allHandlers;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Show available commands";
    }

    @Override
    public String usage() {
        return "help";
    }

    @Override
    public List<ArgDef> arguments() {
        return List.of();
    }

    @Override
    public TerminalResponse execute(Map<String, Object> args, String sessionToken) {
        StringBuilder sb = new StringBuilder("Commands:\n");
        for (CommandHandler h : allHandlers) {
            sb.append(String.format("  /%-20s %s\n", h.name(), h.description()));
        }
        sb.append("\nType a command to get started.");
        return new TerminalResponse(ResponseType.MESSAGE, sb.toString());
    }
}
