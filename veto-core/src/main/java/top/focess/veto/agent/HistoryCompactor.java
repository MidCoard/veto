package top.focess.veto.agent;

import static top.focess.veto.util.LogValues.safe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.loop.CompactionSupport;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;

/** Builds bounded, provenance-preserving summaries without mutating history. */
final class HistoryCompactor {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.HistoryCompactor");
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull BiFunction<ChatMessage, ChatMessage, VetoRequest> requestFactory;
    private final @NonNull Function<VetoRequest, VetoResponse> invoke;

    HistoryCompactor(
            @NonNull ObjectMapper mapper,
            @NonNull BiFunction<ChatMessage, ChatMessage, VetoRequest> requestFactory,
            @NonNull Function<VetoRequest, VetoResponse> invoke) {
        this.objectMapper = mapper;
        this.requestFactory = requestFactory;
        this.invoke = invoke;
    }

    @NonNull String summarize(@NonNull List<TurnRecord> workTurns) {
        if (workTurns.isEmpty()) {
            return "{}";
        }
        List<JsonNode> records = new ArrayList<>();
        for (TurnRecord turn : HistoryProjection.effective(workTurns)) {
            if (turn.type() == TurnType.AGENT_INIT || turn.type() == TurnType.TOKEN_USAGE) continue;
            var record = objectMapper.createObjectNode();
            record.put("number", turn.turnNumber());
            record.put("type", turn.type().name());
            record.set("payload", objectMapper.valueToTree(turn.payload()));
            record.put("origin", CompactionSupport.sourceOrigin(record));
            records.add(record);
        }
        if (records.isEmpty()) return "{}";
        Map<Integer, String> originalOrigins =
                CompactionSupport.sourceOrigins(objectMapper.valueToTree(records));
        List<JsonNode> chunks;
        try {
            // Keep each source's type, number and payload together. A source too large for one
            // bounded input leaves the original history in place instead of losing provenance.
            // There cannot be more chunks than records, so these index/count values bound the
            // rendered header. Include the provider's response wrapper in the actual overhead.
            var largestHeader =
                    PromptCompiler.compileMessage(
                            "runtime-compaction",
                            Map.of("index", records.size(), "count", records.size()));
            var emptyRecords =
                    ChatMessage.user(
                            PromptCompiler.compileText(
                                    "runtime-compaction-records", Map.of("records", List.of())));
            int overhead =
                    compactionInputChars(requestFactory.apply(largestHeader, emptyRecords)) - 2;
            chunks =
                    CompactionSupport.chunks(records, CompactionSupport.MAX_INPUT_CHARS - overhead);
        } catch (IllegalArgumentException oversized) {
            log.warn("Compaction not performed: {}", safe(oversized.getMessage()));
            return "{}";
        }
        List<JsonNode> summaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            JsonNode chunk = chunks.get(i);
            Map<Integer, String> sources = CompactionSupport.sourceOrigins(chunk);
            ChatMessage systemPrompt =
                    PromptCompiler.compileMessage(
                            "runtime-compaction", Map.of("index", i + 1, "count", chunks.size()));
            String rawSummary =
                    callCompactor(
                            systemPrompt,
                            ChatMessage.user(
                                    PromptCompiler.compileText(
                                            "runtime-compaction-records",
                                            Map.of("records", chunk))),
                            sources);
            // Any failed chunk aborts compaction. Never merge a missing chunk away and rewind.
            if ("{}".equals(rawSummary)) return "{}";
            summaries.add(CompactionSupport.validate(rawSummary, sources));
        }
        // Pairwise merge keeps each request bounded even for many source chunks. Each accepted
        // summary is capped at 20k characters, so two fit within the 60k input allowance.
        while (summaries.size() > 1) {
            List<JsonNode> merged = new ArrayList<>();
            for (int i = 0; i < summaries.size(); i += 2) {
                if (i + 1 == summaries.size()) {
                    merged.add(summaries.get(i));
                    continue;
                }
                List<JsonNode> pair = List.of(summaries.get(i), summaries.get(i + 1));
                Map<Integer, String> sources =
                        CompactionSupport.summaryOrigins(pair, originalOrigins);
                String raw =
                        callCompactor(
                                PromptCompiler.compileMessage("runtime-compaction-merge", Map.of()),
                                PromptCompiler.compileMessage(
                                        "runtime-compaction-input", Map.of("summaries", pair)),
                                sources);
                if ("{}".equals(raw)) return "{}";
                merged.add(CompactionSupport.validate(raw, sources));
            }
            summaries = merged;
        }
        return summaries.getFirst().toString();
    }

    private @NonNull String callCompactor(
            @NonNull ChatMessage systemPrompt,
            @NonNull ChatMessage userPrompt,
            @NonNull Map<Integer, String> sourceOrigins) {
        VetoRequest request = requestFactory.apply(systemPrompt, userPrompt);
        if (compactionInputChars(request) > CompactionSupport.MAX_INPUT_CHARS) {
            log.warn("Compaction input exceeds its rendered size limit; original history retained");
            return "{}";
        }
        VetoResponse response = invoke.apply(request);
        String message = response.message();
        if (message == null || message.isBlank()) return "{}";
        try {
            return CompactionSupport.validate(message, sourceOrigins).toString();
        } catch (IllegalArgumentException invalid) {
            log.warn(
                    "Compactor returned an invalid summary; original history will not be compacted: {}",
                    safe(invalid.getMessage()));
            return "{}";
        }
    }

    private int compactionInputChars(@NonNull VetoRequest request) {
        var data =
                new LinkedHashMap<String, Object>(request.responseContract().promptData(request));
        data.put("system", request.systemPrompt());
        return PromptCompiler.compileDocument("provider-native", data).text().length()
                + request.userPrompt().length();
    }
}
