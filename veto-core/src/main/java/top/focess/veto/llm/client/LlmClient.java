package top.focess.veto.llm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.ResolvedRequest;

/**
 * Our abstraction over third-party LLM SDK clients. Encapsulates all provider-specific request
 * building, API calling, and response parsing — so that no SDK types ever leak into provider code.
 *
 * <p>Implementations are SDK-specific adapters (e.g. {@code OpenAiLlmClient}). Providers receive an
 * {@code LlmClient} from {@link LlmClientFactory} and call {@link #complete(ResolvedRequest)} —
 * they never see the underlying SDK client.
 *
 * <p>Plugin providers extend this class to wrap their own SDKs.
 */
public abstract class LlmClient {

    /**
     * Sends the resolved request to the LLM API and returns the raw completion text plus a
     * secret-free summary for audit logging.
     *
     * @param request the resolved request with effective URL and API key
     * @return the raw completion from the provider
     * @throws Exception if the SDK call fails
     */
    public abstract @NonNull RawCompletion complete(@NonNull ResolvedRequest request)
            throws Exception;

    /**
     * Extracts the JSON object(s) from a model response that may have leading/trailing prose or
     * markdown fences. When several top-level objects are present they merge field-wise (later
     * objects fill keys the earlier lack; {@code calls} arrays concatenate) - models occasionally
     * split a veto_pulse response into a message object plus a calls object, and naive parsing
     * would silently drop the second. Shared by the provider clients whose structured-output
     * enforcement is best-effort (DeepSeek json_schema, Anthropic-compatible third parties that
     * ignore a forced tool_choice).
     */
    protected static @NonNull String extractJson(
            @NonNull ObjectMapper objectMapper, @NonNull String content) {
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                int lastFence = trimmed.lastIndexOf("```");
                if (lastFence > firstNewline) {
                    trimmed = trimmed.substring(firstNewline + 1, lastFence).strip();
                }
            }
        }
        // Collect every TOP-LEVEL balanced JSON object as [start, endExclusive) spans. The scan
        // resumes past each closed object so nested objects are not double-counted; a '{' that
        // never closes (a prose brace, a truncated fragment) is skipped.
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        int start = trimmed.indexOf('{');
        while (start >= 0) {
            int end = balancedObjectEnd(trimmed, start);
            if (end < 0) {
                start = trimmed.indexOf('{', start + 1);
                continue;
            }
            spans.add(new int[] {start, end});
            start = trimmed.indexOf('{', end);
        }
        if (spans.isEmpty()) {
            return content;
        }
        if (spans.size() == 1) {
            int[] span = spans.get(0);
            return trimmed.substring(span[0], span[1]);
        }
        com.fasterxml.jackson.databind.node.ObjectNode merged = objectMapper.createObjectNode();
        for (int[] span : spans) {
            try {
                if (objectMapper.readTree(trimmed.substring(span[0], span[1]))
                        instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                    obj.properties()
                            .forEach(
                                    entry -> {
                                        com.fasterxml.jackson.databind.JsonNode existing =
                                                merged.get(entry.getKey());
                                        if ("calls".equals(entry.getKey())
                                                && existing
                                                        instanceof
                                                        com.fasterxml.jackson.databind.node
                                                                        .ArrayNode
                                                                a
                                                && entry.getValue()
                                                        instanceof
                                                        com.fasterxml.jackson.databind.node
                                                                        .ArrayNode
                                                                b) {
                                            a.addAll(b);
                                        } else if (existing == null || existing.isNull()) {
                                            merged.set(entry.getKey(), entry.getValue());
                                        }
                                    });
                }
            } catch (Exception e) {
                // Unparseable fragment - skip it; the remaining objects still merge.
            }
        }
        try {
            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            int[] span = spans.get(0);
            return trimmed.substring(span[0], span[1]);
        }
    }

    /**
     * The end index (exclusive) of the balanced JSON object starting at {@code start} (which must
     * index a '{'), or -1 when the braces never close (a prose '{' or a truncated object). String
     * literals and escapes are honored so braces inside string values do not skew the depth count.
     */
    private static int balancedObjectEnd(@NonNull String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    /**
     * Raw provider output plus a secret-free, audit-safe summary of the request that produced it.
     *
     * @param requestSummary a non-sensitive summary of the request
     * @param rawResponse the raw response string from the provider
     */
    public record RawCompletion(@NonNull String requestSummary, @NonNull String rawResponse) {}
}
