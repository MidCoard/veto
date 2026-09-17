package top.focess.veto.agent.loop;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/** Lossless input grouping and validation for model-authored historical summaries. */
public final class CompactionSupport {
    public static final int MAX_INPUT_CHARS = 60_000;
    public static final int MAX_SUMMARY_CHARS = 20_000;
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();
    private static final @NonNull Set<String> ROOT_FIELDS =
            Set.of("version", "tasks", "user_instructions", "observations", "decisions", "pending");
    private static final @NonNull Set<String> STATES =
            Set.of(
                    "not_started",
                    "in_progress",
                    "completed",
                    "cancelled",
                    "interrupted",
                    "unknown");
    private static final @NonNull Set<String> ORIGINS =
            Set.of("user", "assistant", "tool", "runtime", "summary");

    private CompactionSupport() {}

    /** Never split a turn payload from its source number/type. Oversized records retain history. */
    public static @NonNull List<JsonNode> chunks(@NonNull List<JsonNode> records, int maxChars) {
        List<JsonNode> result = new ArrayList<>();
        ArrayNode current = MAPPER.createArrayNode();
        int size = 2;
        for (JsonNode record : records) {
            int length = record.toString().length();
            if (length + 2 > maxChars)
                throw new IllegalArgumentException(
                        "A history record exceeds the compaction input limit; original history retained");
            int separator = current.isEmpty() ? 0 : 1;
            if (size + separator + length > maxChars) {
                result.add(current);
                current = MAPPER.createArrayNode();
                size = 2;
                separator = 0;
            }
            current.add(record);
            size += separator + length;
        }
        if (!current.isEmpty()) result.add(current);
        return result;
    }

    public static @NonNull Set<Integer> sourceTurns(@NonNull JsonNode records) {
        Set<Integer> result = new HashSet<>();
        for (var record : records) result.add(record.path("number").asInt());
        return result;
    }

    /** Origin comes from durable record metadata, never from the summary's own claims. */
    public static @NonNull String sourceOrigin(@NonNull JsonNode record) {
        JsonNode payload = record.path("payload");
        return switch (record.path("type").asText()) {
            case "USER_PROMPT" -> payload.has("prompt_source") ? "runtime" : "user";
            // The feedback body is user-authored even when replay adds a runtime template.
            case "USER_INTERRUPT" -> "user";
            case "ASSISTANT_THOUGHT", "ASSISTANT_RESPONSE", "TOOL_CALL" -> "assistant";
            case "TOOL_RESPONSE" ->
                    payload.path("call_id").isTextual()
                                    && !payload.path("call_id").asText().isBlank()
                            ? "tool"
                            : "runtime";
            case "COMPACTION_SUMMARY" -> "summary";
            default -> "runtime";
        };
    }

    public static @NonNull Map<Integer, String> sourceOrigins(@NonNull JsonNode records) {
        Map<Integer, String> result = new LinkedHashMap<>();
        for (JsonNode record : records)
            result.put(record.path("number").asInt(), sourceOrigin(record));
        return Map.copyOf(result);
    }

    /** Merge attribution stays tied to original records, not to a model-invented origin. */
    public static @NonNull Map<Integer, String> summaryOrigins(
            @NonNull List<JsonNode> summaries, @NonNull Map<Integer, String> originalOrigins) {
        Map<Integer, String> result = new LinkedHashMap<>();
        for (int source : summarySources(summaries)) {
            String origin = originalOrigins.get(source);
            if (origin == null) throw invalid("unknown merge source");
            result.put(source, origin);
        }
        return Map.copyOf(result);
    }

    /** Sources actually present in these summaries, used to bound each merge's attribution. */
    public static @NonNull Set<Integer> summarySources(@NonNull List<JsonNode> summaries) {
        Set<Integer> result = new HashSet<>();
        for (JsonNode summary : summaries)
            for (String field : ROOT_FIELDS) {
                if (field.equals("version")) continue;
                for (JsonNode item : summary.path(field))
                    for (JsonNode source : item.path("source_turns")) result.add(source.asInt());
            }
        return result;
    }

    /**
     * Validates shape and available source identifiers, not the semantic truth of a claim. Only
     * newly generated summaries use this contract; existing stored summaries remain readable.
     */
    public static @NonNull JsonNode validate(
            @NonNull String raw, @NonNull Set<Integer> sourceTurns) {
        if (raw.length() > MAX_SUMMARY_CHARS) throw invalid("summary exceeds output limit");
        JsonNode root;
        try {
            root =
                    MAPPER.reader()
                            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(raw);
        } catch (Exception error) {
            throw invalid("summary is not JSON");
        }
        if (root == null) throw invalid("missing summary");
        fields(root, ROOT_FIELDS);
        if (!root.path("version").isIntegralNumber()
                || !root.path("version").canConvertToInt()
                || root.path("version").asInt() != 1) throw invalid("unsupported summary version");
        int entries = 0;
        for (String name : ROOT_FIELDS) {
            if (name.equals("version")) continue;
            JsonNode items = root.path(name);
            if (!items.isArray()) throw invalid(name + " must be an array");
            entries += items.size();
            for (JsonNode item : items) {
                boolean task = name.equals("tasks");
                boolean observation = name.equals("observations");
                fields(
                        item,
                        task
                                ? Set.of("request", "state", "source_turns")
                                : observation
                                        ? Set.of("text", "origin", "source_turns")
                                        : Set.of("text", "source_turns"));
                String textKey = task ? "request" : "text";
                if (!item.path(textKey).isTextual() || item.path(textKey).asText().isBlank())
                    throw invalid(name + " entry requires nonblank " + textKey);
                if (task && !STATES.contains(item.path("state").asText()))
                    throw invalid("unknown task state");
                if (observation && !ORIGINS.contains(item.path("origin").asText()))
                    throw invalid("unknown observation origin");
                JsonNode sources = item.path("source_turns");
                if (!sources.isArray() || sources.isEmpty())
                    throw invalid("source_turns must be nonempty");
                for (JsonNode source : sources)
                    if (!source.isIntegralNumber()
                            || !source.canConvertToInt()
                            || !sourceTurns.contains(source.asInt()))
                        throw invalid("source_turns must refer to supplied history records");
            }
        }
        if (entries == 0) throw invalid("summary contains no attributed information");
        return root;
    }

    /**
     * Also checks that a claimed source role has a matching supplied record. This verifies
     * attribution categories, not that the cited record semantically proves the model's claim. Old
     * stored summaries are replayed as historical data and are not retroactively validated.
     */
    public static @NonNull JsonNode validate(
            @NonNull String raw, @NonNull Map<Integer, String> origins) {
        JsonNode root = validate(raw, new HashSet<Integer>(origins.keySet()));
        for (JsonNode instruction : root.path("user_instructions"))
            requireOrigin(instruction, "user", origins);
        for (JsonNode observation : root.path("observations"))
            requireOrigin(observation, observation.path("origin").asText(), origins);
        return root;
    }

    private static void requireOrigin(
            @NonNull JsonNode item,
            @NonNull String claimed,
            @NonNull Map<Integer, String> origins) {
        for (JsonNode source : item.path("source_turns"))
            if (claimed.equals(origins.get(source.asInt()))) return;
        throw invalid("claimed " + claimed + " origin has no matching source record");
    }

    private static void fields(@NonNull JsonNode node, @NonNull Set<String> expected) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !actual.equals(expected))
            throw invalid("summary fields do not match the record contract");
    }

    private static @NonNull IllegalArgumentException invalid(@NonNull String reason) {
        return new IllegalArgumentException("Invalid compaction summary: " + reason);
    }
}
