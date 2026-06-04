package top.focess.veto.command;

import top.focess.command.Command;
import top.focess.command.CommandArgument;
import top.focess.command.CommandResult;
import top.focess.command.DataConverter;

public abstract class VetoCommand extends Command {

    private final String description;

    protected VetoCommand(String name, String description, String... aliases) {
        super(name, aliases);
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    protected static CommandResult allow() {
        return CommandResult.ALLOW;
    }

    protected static CommandResult refuse() {
        return CommandResult.REFUSE;
    }

    protected static CommandArgument<String> arg(String name) {
        return CommandArgument.ofString().named(name);
    }

    protected static CommandArgument<String> opt(String name) {
        return CommandArgument.ofNullable(DataConverter.DEFAULT_DATA_CONVERTER).named(name);
    }

    protected static CommandArgument<String> fixed(String value) {
        return CommandArgument.of(value);
    }
}
