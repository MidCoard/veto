package top.focess.veto.llm.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.NonNull;

/** Optional active references in both autonomous answers and guided generation. */
public final class CitationSchema {
    private CitationSchema() {}

    public static @NonNull ObjectNode create(@NonNull ObjectMapper mapper) {
        ObjectNode array = mapper.createObjectNode().put("type", "array").put("maxItems", 32);
        array.put(
                "description",
                "Sources for [label](cite:id) links in message. Count actual non-system input messages from zero; quote only the specified message, verbatim. Ordinary blockquotes do not declare sources.");
        ObjectNode item =
                array.putObject("items").put("type", "object").put("additionalProperties", false);
        item.putArray("required").add("id").add("sources");
        ObjectNode properties = item.putObject("properties");
        properties.putObject("id").put("type", "string").put("pattern", "^[A-Za-z0-9_-]{1,64}$");
        ObjectNode sources =
                properties
                        .putObject("sources")
                        .put("type", "array")
                        .put("minItems", 1)
                        .put("maxItems", 8);
        ObjectNode source =
                sources.putObject("items").put("type", "object").put("additionalProperties", false);
        source.putArray("required").add("message_index").add("quote");
        ObjectNode fields = source.putObject("properties");
        fields.putObject("message_index").put("type", "integer").put("minimum", 0);
        fields.putObject("quote").put("type", "string").put("minLength", 1).put("maxLength", 4000);
        return array;
    }
}
