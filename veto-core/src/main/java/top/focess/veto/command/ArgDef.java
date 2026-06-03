package top.focess.veto.command;

/**
 * Describes one argument of a command.
 */
public record ArgDef(String name, String type, boolean required, String description) {
}
