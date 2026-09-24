package top.focess.veto.api.plugin.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Converts ordinary JSON trees into the bounded plugin wire contract. */
@NullMarked
public final class JsonValues {
    private JsonValues() {}

    public static Map<String, @Nullable Object> toMap(JsonValue.ObjectValue value) {
        Map<String, @Nullable Object> result = new LinkedHashMap<>();
        value.values().forEach((key, item) -> result.put(key, toJava(item)));
        return result;
    }

    private static @Nullable Object toJava(JsonValue value) {
        return switch (value) {
            case JsonValue.NullValue ignored -> null;
            case JsonValue.StringValue item -> item.value();
            case JsonValue.BooleanValue item -> item.value();
            case JsonValue.NumberValue item -> item.value();
            case JsonValue.ArrayValue item ->
                    item.values().stream().map(JsonValues::toJava).toList();
            case JsonValue.ObjectValue item -> toMap(item);
        };
    }

    public static JsonValue from(JsonNode node) {
        if (node.isNull()) return JsonValue.NullValue.INSTANCE;
        if (node.isTextual()) return new JsonValue.StringValue(node.textValue());
        if (node.isBoolean()) return new JsonValue.BooleanValue(node.booleanValue());
        if (node.isNumber()) return new JsonValue.NumberValue(node.decimalValue());
        if (node.isArray()) {
            var values = new ArrayList<JsonValue>();
            node.forEach(value -> values.add(from(value)));
            return new JsonValue.ArrayValue(values);
        }
        if (node.isObject()) {
            var values = new LinkedHashMap<String, JsonValue>();
            node.properties().forEach(entry -> values.put(entry.getKey(), from(entry.getValue())));
            return new JsonValue.ObjectValue(values);
        }
        throw new IllegalArgumentException("Unsupported JSON node");
    }
}
