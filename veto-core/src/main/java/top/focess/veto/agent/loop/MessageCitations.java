package top.focess.veto.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.llm.core.ProviderMessages;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.session.QuoteCheckService.Check;
import top.focess.veto.session.QuoteCheckService.Match;
import top.focess.veto.session.QuoteCheckService.Reference;

/** Resolves explicit message references before the successful request leaves the runner. */
public final class MessageCitations {
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    private MessageCitations() {}

    public record Bound(
            @NonNull String requestId,
            @NonNull String provider,
            @NonNull String model,
            int messageCount,
            @NonNull List<@NonNull Check> checks) {}

    public static @NonNull Bound bind(
            @NonNull VetoRequest request,
            @NonNull VetoResponse response,
            @NonNull List<TurnRecord> history) {
        var messages = ProviderMessages.groups(request);
        var checks = new ArrayList<Check>();
        var records = new HashMap<Integer, TurnRecord>();
        for (var record : history) records.put(record.turnNumber(), record);
        var citations = response.citations();
        if (citations != null) {
            var ids = new HashSet<String>();
            for (var citation : citations.stream().limit(32).toList()) {
                var matches = new ArrayList<Match>();
                var references = new ArrayList<Reference>();
                if (!ids.add(citation.id())) continue;
                for (var source : citation.sources().stream().limit(8).toList()) {
                    int index = source.messageIndex();
                    String quote = source.quote();
                    var found = new ArrayList<Match>();
                    boolean visible = false;
                    if (index >= 0
                            && index < messages.size()
                            && !quote.isBlank()
                            && quote.length() <= 4000) {
                        for (var message : messages.get(index)) {
                            if (!contains(message.content(), quote)
                                    && !contains(message.toolArgs(), quote)) continue;
                            visible = true;
                            for (var sourceTurn : message.sourceTurns()) {
                                var record = records.get(sourceTurn);
                                if (record == null) continue;
                                JsonNode payload = MAPPER.valueToTree(record.payload());
                                if (payload != null)
                                    collect(
                                            record.turnNumber(),
                                            record.type(),
                                            payload,
                                            quote,
                                            found);
                            }
                        }
                    }
                    String status =
                            !found.isEmpty() ? "matched" : visible ? "unavailable" : "not_found";
                    references.add(new Reference(index, status));
                    for (var match : found) if (!matches.contains(match)) matches.add(match);
                }
                String status =
                        references.isEmpty()
                                ? "unavailable"
                                : references.stream()
                                                .allMatch(ref -> ref.status().equals("not_found"))
                                        ? "not_found"
                                        : references.stream()
                                                        .anyMatch(
                                                                ref ->
                                                                        !ref.status()
                                                                                .equals("matched"))
                                                ? "unavailable"
                                                : matches.size() > 1 ? "ambiguous" : "matched";
                checks.add(
                        new Check(
                                citation.id(),
                                status,
                                List.copyOf(matches),
                                List.copyOf(references)));
            }
        }
        return new Bound(
                UUID.randomUUID().toString(),
                request.providerType().name(),
                request.modelName(),
                messages.size(),
                List.copyOf(checks));
    }

    private static boolean contains(String text, @NonNull String quote) {
        if (text == null) return false;
        if (text.contains(quote)) return true;
        return contains(json(text), quote);
    }

    private static boolean contains(@NonNull JsonNode node, @NonNull String quote) {
        if (node.isTextual()) {
            String text = node.asText();
            if (text.contains(quote)) return true;
            if (text.startsWith("{") || text.startsWith("[")) return contains(json(text), quote);
            return false;
        }
        for (var child : node) if (contains(child, quote)) return true;
        return false;
    }

    private static @NonNull JsonNode json(@NonNull String text) {
        try {
            var value = MAPPER.readTree(text);
            return value == null ? MAPPER.createObjectNode() : value;
        } catch (Exception ignored) {
            return MAPPER.createObjectNode();
        }
    }

    private static void collect(
            int turn,
            @NonNull TurnType type,
            @NonNull JsonNode payload,
            @NonNull String quote,
            @NonNull List<Match> matches) {
        String content = payload.path("content").asText();
        var result = json(content);
        if (result.has("format") && result.path("content").isTextual())
            result = json(result.path("content").asText());
        int before = matches.size();
        int index = 0;
        if (type == TurnType.TOOL_RESPONSE && payload.path("success").asBoolean())
            for (var evidence : result.path("evidence")) {
                add(
                        turn,
                        "web",
                        "evidence[" + index++ + "].quote",
                        evidence.path("url").asText(result.path("url").asText()),
                        evidence.path("quote").asText(),
                        quote,
                        matches);
            }
        if (before == matches.size())
            add(turn, "conversation", "content", "", content, quote, matches);
        for (String field : List.of("feedback", "response"))
            add(turn, "conversation", field, "", payload.path(field).asText(), quote, matches);
        if (before == matches.size()) {
            for (String field : List.of("content", "feedback", "response", "args"))
                collectJson(turn, payload.path(field), List.of(field), quote, matches, 0);
        }
    }

    /** A JSON path is metadata, never an instruction or marker inserted into source text. */
    private static void collectJson(
            int turn,
            @NonNull JsonNode value,
            @NonNull List<String> path,
            @NonNull String quote,
            @NonNull List<Match> matches,
            int depth) {
        if (depth > 24 || value.isMissingNode() || value.isNull()) return;
        if (value.isValueNode()) {
            String text = value.asText();
            JsonNode locator = MAPPER.valueToTree(path);
            if (locator != null)
                add(turn, "conversation", "json:" + locator, "", text, quote, matches);
            if (value.isTextual()
                    && !text.contains(quote)
                    && (text.startsWith("{") || text.startsWith("[")))
                collectJson(turn, json(text), path, quote, matches, depth + 1);
        } else if (value.isObject()) {
            for (var entry : value.properties()) {
                var child = new ArrayList<>(path);
                child.add(entry.getKey());
                collectJson(turn, entry.getValue(), child, quote, matches, depth + 1);
            }
        } else if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                var child = new ArrayList<>(path);
                child.add(Integer.toString(index));
                collectJson(turn, value.path(index), child, quote, matches, depth + 1);
            }
        }
    }

    private static void add(
            int turn,
            @NonNull String kind,
            @NonNull String field,
            @NonNull String url,
            @NonNull String text,
            @NonNull String quote,
            @NonNull List<Match> matches) {
        int start = text.indexOf(quote);
        if (start < 0) return;
        int end = start + quote.length();
        int from = Math.max(0, start - 160);
        int to = Math.min(text.length(), end + 160);
        matches.add(
                new Match(
                        turn,
                        kind,
                        field,
                        url,
                        "exact",
                        text.substring(from, to),
                        start - from,
                        end - from,
                        start,
                        end));
    }
}
