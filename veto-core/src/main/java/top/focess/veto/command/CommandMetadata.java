package top.focess.veto.command;

import java.util.List;

/**
 * Serializable metadata for a command — used to generate help text.
 */
public record CommandMetadata(String name, String description, String usage, List<ArgDef> args) {
}
