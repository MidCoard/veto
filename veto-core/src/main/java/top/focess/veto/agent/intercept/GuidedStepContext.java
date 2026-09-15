package top.focess.veto.agent.intercept;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Runtime-owned provenance; labels are model-authored data, never authorization. */
public record GuidedStepContext(
        String programModelCallId,
        @NonNull String stepId,
        int stepIndex,
        @NonNull String label,
        @NonNull Map<String, String> inputSources) {
    public GuidedStepContext {
        inputSources = Map.copyOf(inputSources);
    }

    public static @NonNull Map<String, String> sources(
            @NonNull ObjectMapper mapper,
            @NonNull Map<String, Object> inputs,
            @NonNull Map<String, String> producers) {
        Map<String, String> result = new LinkedHashMap<>();
        inputs.forEach((key, value) -> collect(mapper.valueToTree(value), key, producers, result));
        return result;
    }

    private static void collect(
            @NonNull JsonNode value,
            @NonNull String path,
            @NonNull Map<String, String> producers,
            @NonNull Map<String, String> result) {
        if (value.isTextual()) {
            String text = value.asText();
            if (text.startsWith("$") && !text.startsWith("$$"))
                result.put(
                        path,
                        producers.getOrDefault(text.substring(1), "runtime:" + text.substring(1)));
        } else if (value.isObject()) {
            value.properties()
                    .forEach(
                            entry ->
                                    collect(
                                            entry.getValue(),
                                            path + "." + entry.getKey(),
                                            producers,
                                            result));
        } else if (value.isArray()) {
            for (int i = 0; i < value.size(); i++)
                collect(value.path(i), path + "[" + i + "]", producers, result);
        }
    }
}
