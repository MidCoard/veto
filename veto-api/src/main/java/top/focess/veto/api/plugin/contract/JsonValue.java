package top.focess.veto.api.plugin.contract;

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
    /** JSON {@code null} singleton. */
    enum NullValue implements JsonValue {
        /** The only JSON-null value. */
        INSTANCE
    }

    /**
     * JSON boolean value.
     *
     * @param value JSON boolean value
     */
    record BooleanValue(boolean value) implements JsonValue {}

    /**
     * Bounded JSON number.
     *
     * @param value bounded finite decimal representation of a JSON number
     */
    record NumberValue(@NonNull BigDecimal value) implements JsonValue {
        /** Validates the bounded JSON number representation. */
        public NumberValue {
            if (value.precision() > 128 || value.scale() < -1024 || value.scale() > 1024)
                throw new IllegalArgumentException("JSON number limit exceeded");
        }

        /**
         * @return a redacted representation that does not expose the numeric value
         */
        @Override
        public @NonNull String toString() {
            return "[JSON number]";
        }
    }

    /**
     * Bounded JSON string.
     *
     * @param value bounded JSON string
     */
    record StringValue(@NonNull String value) implements JsonValue {
        /** Validates the JSON string size limit. */
        public StringValue {
            if (value.length() > 65536)
                throw new IllegalArgumentException("JSON text limit exceeded");
        }

        /**
         * @return a redacted representation that does not expose the string value
         */
        @Override
        public @NonNull String toString() {
            return "[JSON string]";
        }
    }

    /**
     * Bounded JSON array.
     *
     * @param values immutable JSON elements copied on construction
     */
    record ArrayValue(@NonNull List<@NonNull JsonValue> values) implements JsonValue {
        /** Defensively copies and validates the complete JSON tree. */
        public ArrayValue {
            values = List.copyOf(values);
            Budget budget = new Budget();
            for (var value : values) budget.visit(value, 1);
        }

        /**
         * @return a redacted representation that does not expose array contents
         */
        @Override
        public @NonNull String toString() {
            return "[JSON array]";
        }
    }

    /**
     * Bounded JSON object.
     *
     * @param values immutable JSON members copied on construction
     */
    record ObjectValue(@NonNull Map<@NonNull String, @NonNull JsonValue> values)
            implements JsonValue {
        /** Defensively copies and validates the complete JSON tree. */
        public ObjectValue {
            values = Map.copyOf(values);
            Budget budget = new Budget();
            for (var entry : values.entrySet()) {
                budget.text(entry.getKey());
                budget.visit(entry.getValue(), 1);
            }
        }

        /**
         * @return a redacted representation that does not expose object members
         */
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
