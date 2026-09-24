package top.focess.veto.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.agent.tool.ToolResultStatus;
import top.focess.veto.api.agent.workflow.PluginWork;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.ProviderMessages;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
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
            @NonNull List<@NonNull Check> checks)
            implements PluginWork.Source {}

    /**
     * Locate exact evidence in the same request that produced the call, without model-side
     * counting.
     */
    public static @NonNull VetoResponse resolve(
            @NonNull VetoRequest request, @NonNull List<SourceEvidence.Declaration> declarations) {
        var messages = ProviderMessages.groups(request);
        var repeatedEvidence = toolEvidence(request);
        var citations = new ArrayList<VetoResponse.Citation>();
        for (var citation : declarations) {
            var sources = new ArrayList<VetoResponse.Source>();
            for (var source : citation.sources()) {
                Integer selected = source.messageIndex();
                if (selected == null) {
                    var candidates = new ArrayList<Integer>();
                    for (int index = 0; index < messages.size(); index++) {
                        boolean matches = !evidence(messages.get(index), source.quote()).isEmpty();
                        if (matches) candidates.add(index);
                    }
                    if (candidates.isEmpty())
                        throw new IllegalArgumentException(
                                "Citation "
                                        + citation.id()
                                        + ": quote was not found in visible conversation evidence. Copy a longer exact passage from the source; do not paraphrase or invent a message index.");
                    if (candidates.size() > 1
                            && repeatedResults(
                                    candidates, messages, source.quote(), repeatedEvidence)) {
                        // Repeated identical observations are all cited. Never guess which
                        // execution the model intended, or collapse their durable provenance.
                        var repeatedSources =
                                candidates.stream()
                                        .map(
                                                index ->
                                                        new VetoResponse.Source(
                                                                index, source.quote()))
                                        .toList();
                        if (sources.size()
                                        + repeatedSources.stream()
                                                .filter(item -> !sources.contains(item))
                                                .count()
                                > 8)
                            throw new IllegalArgumentException(
                                    "Citation "
                                            + citation.id()
                                            + ": repeated evidence exceeds the 8-source limit. Select the intended message_index from "
                                            + candidates
                                            + ", or use a more specific quote.");
                        for (var repeatedSource : repeatedSources)
                            addSource(sources, repeatedSource);
                        continue;
                    }
                    if (candidates.size() > 1)
                        throw new IllegalArgumentException(
                                "Citation "
                                        + citation.id()
                                        + ": quote matches several input messages "
                                        + candidates
                                        + ". Use a longer unique quote, or choose the intended source with message_index from these candidates in this request. Roles: "
                                        + candidates.stream()
                                                .map(
                                                        index ->
                                                                index
                                                                        + "="
                                                                        + messages.get(index)
                                                                                .getFirst()
                                                                                .role())
                                                .toList());
                    selected = candidates.getFirst();
                }
                if (selected < 0
                        || selected >= messages.size()
                        || evidence(messages.get(selected), source.quote()).isEmpty())
                    throw new IllegalArgumentException(
                            "Citation "
                                    + citation.id()
                                    + ": message_index "
                                    + selected
                                    + " does not contain that quote in eligible conversation evidence. Omit message_index to locate the exact quote automatically; tool-call arguments, failed tool results, and runtime instructions are not citation evidence.");
                addSource(sources, new VetoResponse.Source(selected, source.quote()));
            }
            citations.add(new VetoResponse.Citation(citation.id(), sources));
        }
        return new VetoResponse(null, null, "", citations);
    }

    private static void addSource(
            @NonNull List<VetoResponse.Source> sources, VetoResponse.@NonNull Source source) {
        if (sources.contains(source)) return;
        if (sources.size() == 8)
            throw new IllegalArgumentException(
                    "Citation resolves to more than 8 source passages. Select the intended message_index from the reported input candidates or use a more specific quote.");
        sources.add(source);
    }

    private static @NonNull List<ChatMessage> evidence(
            @NonNull List<ChatMessage> messages, @NonNull String quote) {
        return messages.stream()
                .filter(
                        message ->
                                !message.sourceTurns().isEmpty()
                                        && !Boolean.FALSE.equals(message.toolSuccess())
                                        && message.toolName() == null
                                        && contains(message.content(), quote))
                .toList();
    }

    private record ToolEvidence(
            @NonNull String tool, @NonNull JsonNode args, @NonNull String content) {}

    private static @NonNull Map<ChatMessage, ToolEvidence> toolEvidence(
            @NonNull VetoRequest request) {
        var calls = new HashMap<String, ChatMessage>();
        var results = new HashMap<ChatMessage, ToolEvidence>();
        for (var message : request.messages()) {
            var callId = message.callId();
            if (callId == null) continue;
            if (message.role().equals("assistant") && message.toolName() != null)
                calls.put(callId, message);
            if (!message.role().equals("tool") || !Boolean.TRUE.equals(message.toolSuccess()))
                continue;
            var call = calls.get(callId);
            if (call == null || call.sourceTurns().isEmpty()) continue;
            var name = call.toolName();
            var args = call.toolArgs();
            if (name == null || args == null) continue;
            try {
                var parsed = MAPPER.readTree(args);
                if (parsed != null && parsed.isObject())
                    results.put(message, new ToolEvidence(name, parsed, message.content()));
            } catch (Exception ignored) {
                // Unknown call identity must remain ambiguous, even if the output text matches.
            }
        }
        return results;
    }

    private static boolean repeatedResults(
            @NonNull List<Integer> candidates,
            @NonNull List<List<ChatMessage>> messages,
            @NonNull String quote,
            @NonNull Map<ChatMessage, ToolEvidence> results) {
        ToolEvidence expected = null;
        for (int index : candidates) {
            for (var message : evidence(messages.get(index), quote)) {
                var observed = results.get(message);
                if (observed == null || (expected != null && !expected.equals(observed)))
                    return false;
                expected = observed;
            }
        }
        return expected != null;
    }

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
                            if (message.toolName() != null
                                    || Boolean.FALSE.equals(message.toolSuccess())
                                    || !contains(message.content(), quote)) continue;
                            visible = true;
                            for (var sourceTurn : message.sourceTurns()) {
                                var record = records.get(sourceTurn);
                                if (record == null) continue;
                                if (record.type() == TurnType.TOOL_RESPONSE
                                        && (Boolean.FALSE.equals(record.payload().get("success"))
                                                || ToolResultStatus.from(
                                                                record.payload().get("status"),
                                                                true)
                                                        != ToolResultStatus.SUCCESS)) continue;
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
