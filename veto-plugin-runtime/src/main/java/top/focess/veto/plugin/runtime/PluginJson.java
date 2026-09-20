package top.focess.veto.plugin.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.extension.contract.JsonValue;

/** JSON transport conversion only; plugin contracts do not depend on Jackson. */
public final class PluginJson {
    private PluginJson() {}

    public static @NonNull JsonValue fromNode(@NonNull JsonNode node) {
        if (node.isNull()) return JsonValue.NullValue.INSTANCE;
        if (node.isBoolean()) return new JsonValue.BooleanValue(node.booleanValue());
        if (node.isNumber()) return new JsonValue.NumberValue(node.decimalValue());
        if (node.isTextual()) return new JsonValue.StringValue(node.asText());
        if (node.isArray()) {
            var values = new ArrayList<JsonValue>();
            for (var item : node) values.add(fromNode(item));
            return new JsonValue.ArrayValue(values);
        }
        if (node.isObject()) {
            var fields = new LinkedHashMap<String, JsonValue>();
            node.properties()
                    .forEach(entry -> fields.put(entry.getKey(), fromNode(entry.getValue())));
            return new JsonValue.ObjectValue(fields);
        }
        throw new IllegalArgumentException("Unsupported plugin JSON value");
    }

    public static JsonValue.@NonNull ObjectValue object(@NonNull JsonNode node) {
        if (fromNode(node) instanceof JsonValue.ObjectValue object) return object;
        throw new IllegalArgumentException("Expected plugin JSON object");
    }

    public static @NonNull JsonNode toNode(@NonNull JsonValue value) {
        var json = JsonNodeFactory.instance;
        return switch (value) {
            case JsonValue.NullValue ignored -> json.nullNode();
            case JsonValue.BooleanValue item -> json.booleanNode(item.value());
            case JsonValue.NumberValue item -> {
                var number = item.value().stripTrailingZeros();
                if (number.scale() > 0) yield json.numberNode(number);
                var integer = number.toBigIntegerExact();
                if (integer.bitLength() <= 31) yield json.numberNode(integer.intValue());
                if (integer.bitLength() <= 63) yield json.numberNode(integer.longValue());
                yield json.numberNode(integer);
            }
            case JsonValue.StringValue item -> json.textNode(item.value());
            case JsonValue.ArrayValue items -> {
                var result = json.arrayNode();
                for (var item : items.values()) result.add(toNode(item));
                yield result;
            }
            case JsonValue.ObjectValue fields -> {
                var result = json.objectNode();
                fields.values().forEach((key, item) -> result.set(key, toNode(item)));
                yield result;
            }
        };
    }
}
