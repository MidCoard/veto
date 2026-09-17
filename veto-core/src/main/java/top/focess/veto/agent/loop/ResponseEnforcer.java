package top.focess.veto.agent.loop;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.ModelSchemaException;

/** Runtime enforcement of the response contract, independent of provider schema support. */
public final class ResponseEnforcer {
    private static final @NonNull Pattern BARE_CITATION =
            Pattern.compile("\\[citation:([A-Za-z0-9_-]+)]");

    private static final @NonNull Pattern CITATION_LINK = Pattern.compile("\\]\\(cite:([^)]*)\\)");
    private static final @NonNull Pattern CODE =
            Pattern.compile("(?s)```.*?```|~~~.*?~~~|`[^`\\n]*`");

    private ResponseEnforcer() {}

    public static @NonNull VetoResponse enforce(@NonNull VetoResponse response) {
        return enforce(response, Set.of());
    }

    public static @NonNull VetoResponse enforce(
            @NonNull VetoResponse response, @NonNull Set<@NonNull String> allowedToolNames) {
        var calls = response.calls();
        if (calls != null) {
            if (calls.isEmpty())
                throw new ModelSchemaException("calls must be non-empty when present");
            for (var call : calls) {
                if (!allowedToolNames.isEmpty() && !allowedToolNames.contains(call.toolName())) {
                    throw new ModelSchemaException(
                            "calls[].tool_name must exactly name a catalog tool; '"
                                    + call.toolName()
                                    + "' is not in this turn's tool catalog");
                }
            }
        }
        String message = response.message();
        if (message != null && BARE_CITATION.matcher(message).find())
            throw new ModelSchemaException(
                    "Bare [citation:id] markers cannot identify a source. Use [label](cite:id)"
                            + " in message and declare the same id in citations with sources"
                            + " containing message_index and an exact quote from that message.");
        var citations = response.citations();
        if (citations != null) {
            if (citations.size() > 32)
                throw new ModelSchemaException("At most 32 citations are allowed");
            var ids = new HashSet<String>();
            for (var citation : citations) {
                if (!ids.add(citation.id()))
                    throw new ModelSchemaException("Citations need unique ids");
                if (message == null || !message.contains("](cite:" + citation.id() + ")"))
                    throw new ModelSchemaException(
                            "Each declared citation must be linked in message using [label](cite:"
                                    + citation.id()
                                    + "). Bare [citation:id] markers are not links. Preserve the"
                                    + " source declarations and use the required Markdown link syntax.");
            }
        }
        if (message != null) {
            var ids = new HashSet<String>();
            if (citations != null) for (var citation : citations) ids.add(citation.id());
            var links = CITATION_LINK.matcher(CODE.matcher(message).replaceAll(""));
            while (links.find()) {
                String id = links.group(1);
                if (id == null || !ids.contains(id))
                    throw new ModelSchemaException(
                            "A [label](cite:id) link requires a verified source declaration. Call answer_with_citations with message and citations containing exact source quotes, or answer in ordinary text without a cite: link. Handwritten links alone cannot create source metadata.");
            }
        }
        if (calls == null && (message == null || message.isBlank()))
            throw new ModelSchemaException("message required (no native tool calls to execute)");
        return response;
    }
}
