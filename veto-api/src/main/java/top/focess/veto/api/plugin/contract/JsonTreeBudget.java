package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/** Counts the nodes, text and nesting of one JSON tree. Confined to its validating caller. */
final class JsonTreeBudget {
    private int nodes = 1;
    private int characters;

    void text(@NonNull String value) {
        if (value.length() > 65536 - characters)
            throw new IllegalArgumentException("JSON text limit exceeded");
        characters += value.length();
    }

    void visit(@NonNull JsonValue value, int depth) {
        if (++nodes > 4096) throw new IllegalArgumentException("JSON node limit exceeded");
        switch (value) {
            case JsonValue.StringValue string -> text(string.value());
            case JsonValue.ArrayValue array -> {
                if (depth >= 32) throw new IllegalArgumentException("JSON depth limit exceeded");
                for (var child : array.values()) visit(child, depth + 1);
            }
            case JsonValue.ObjectValue object -> {
                if (depth >= 32) throw new IllegalArgumentException("JSON depth limit exceeded");
                for (var entry : object.values().entrySet()) {
                    text(entry.getKey());
                    visit(entry.getValue(), depth + 1);
                }
            }
            default -> {}
        }
    }
}
