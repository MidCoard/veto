package top.focess.veto.plugin.contract;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Immutable JSON-only boundary. Each tree is limited to 32 container levels, 4096 nodes, and 65536
 * UTF-16 text units (keys included). Wire parsers must ALSO bound input bytes before constructing
 * values. No Java serialization or arbitrary object payloads.
 */
public sealed interface JsonValue {
    enum NullValue implements JsonValue {
        INSTANCE
    }

    record BooleanValue(boolean value) implements JsonValue {}

    record NumberValue(@NonNull BigDecimal value) implements JsonValue {
        public NumberValue {
            if (value.precision() > 128 || value.scale() < -1024 || value.scale() > 1024)
                throw new IllegalArgumentException("JSON number limit exceeded");
        }

        @Override
        public @NonNull String toString() {
            return "[JSON number]";
        }
    }

    record StringValue(@NonNull String value) implements JsonValue {
        public StringValue {
            if (value.length() > 65536)
                throw new IllegalArgumentException("JSON text limit exceeded");
        }

        @Override
        public @NonNull String toString() {
            return "[JSON string]";
        }
    }

    record ArrayValue(@NonNull List<@NonNull JsonValue> values) implements JsonValue {
        public ArrayValue {
            values = List.copyOf(values);
            Budget budget = new Budget();
            for (var value : values) budget.visit(value, 1);
        }

        @Override
        public @NonNull String toString() {
            return "[JSON array]";
        }
    }

    record ObjectValue(@NonNull Map<@NonNull String, @NonNull JsonValue> values)
            implements JsonValue {
        public ObjectValue {
            values = Map.copyOf(values);
            Budget budget = new Budget();
            for (var entry : values.entrySet()) {
                budget.text(entry.getKey());
                budget.visit(entry.getValue(), 1);
            }
        }

        @Override
        public @NonNull String toString() {
            return "[JSON object]";
        }
    }
}

final class Budget {
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
