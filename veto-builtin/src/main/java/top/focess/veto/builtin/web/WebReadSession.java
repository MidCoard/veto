package top.focess.veto.builtin.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.http.ApprovedHttpDestination;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.builtin.web.model.*;
import top.focess.veto.builtin.web.model.Execution;
import top.focess.veto.builtin.web.model.FinishReadArgs;
import top.focess.veto.builtin.web.model.Result;

/** Document state and effect boundary owned by exactly one reader AgentRunner. */
public final class WebReadSession implements WebDocumentCapability, IsolatedAgent.Tools {
    private static final int MAX_ANSWER_CHARS = 4000;
    private static final int MAX_EVIDENCE = 8;
    private static final int OUTLINE_ENTRIES = 24;
    private final @NonNull ObjectMapper mapper = new ObjectMapper();
    private final IsolatedAgent.@NonNull Runtime runtime;
    private volatile boolean closed;
    private final @NonNull ApprovedHttpDestination destination;
    private WebReadDocument document;
    private volatile Result result;
    private volatile ToolExecutionException failure;

    public WebReadSession(
            IsolatedAgent.@NonNull Runtime runtime, @NonNull ApprovedHttpDestination destination) {
        this.runtime = runtime;
        this.destination = destination;
    }

    public @NonNull List<NativeTool<?>> tools() {
        return List.of(
                new FetchPageTool(this),
                new ReadSectionsTool(this),
                new FindSectionsTool(this),
                new FinishReadTool(this));
    }

    private void authorize(@NonNull String operation) {
        if (closed) throw new SecurityException("Reader invocation has ended");
        runtime.authorize(operation);
        var failed = failure;
        if (failed != null) throw failed;
    }

    @Override
    public @NonNull String fetchPage() {
        authorize("fetch_page");
        try {
            WebReadDocument current = document;
            if (current == null) {
                current = new WebReadDocument(fetched());
                document = current;
            }
            var outline = current.outline();
            int count = Math.min(OUTLINE_ENTRIES, outline.size());
            while (true) {
                String observation =
                        json(
                                Map.of(
                                        "outline", outline.subList(0, count),
                                        "segmentCount", outline.size(),
                                        "truncated", current.truncated()));
                if (observation.getBytes(StandardCharsets.UTF_8).length
                        <= runtime.observationBudgetBytes()) return observation;
                if (count == 0)
                    return ToolErrors.failure(
                            ToolErrorCode.READER.READER_OBSERVATION,
                            "Observation budget: no budget remains for the page outline.");
                count--;
            }
        } catch (ToolExecutionException error) {
            failure = error;
            throw error;
        }
    }

    @Override
    public @NonNull String findSections(@NonNull String query) {
        authorize("find_sections");
        return json(document().find(query));
    }

    @Override
    public @NonNull String readSections(@NonNull List<@NonNull String> ids) {
        authorize("read_sections");
        var current = document();
        String observation = json(current.read(ids));
        if (observation.getBytes(StandardCharsets.UTF_8).length > runtime.observationBudgetBytes())
            return ToolErrors.failure(
                    ToolErrorCode.READER.READER_OBSERVATION,
                    "Observation budget: these sections exceed the reading budget. Read fewer IDs"
                            + " per call.");
        current.recordInspection(ids);
        return observation;
    }

    @Override
    public @NonNull String finish(@NonNull FinishReadArgs value) {
        authorize("finish_read");
        Result completed = finish(value, document(), execution());
        String content = json(completed);
        runtime.authorize("finish_read");
        result = completed;
        runtime.complete(content);
        return content;
    }

    private @NonNull WebReadDocument document() {
        WebReadDocument current = document;
        if (current == null)
            return ToolErrors.failure(
                    ToolErrorCode.READER.READER_DOCUMENT,
                    "Document not fetched: fetch the page first with fetch_page.");
        return current;
    }

    private @NonNull FetchedPage fetched() {
        var page = destination.fetch();
        return new FetchedPage(
                page.uri(),
                page.status(),
                page.contentType(),
                page.content(),
                page.truncated(),
                page.maxChars());
    }

    private @NonNull Execution execution() {
        var usage = runtime.usage();
        return new Execution(
                usage.id(),
                usage.model(),
                usage.elapsedMillis(),
                usage.calls(),
                usage.inputTokens(),
                usage.outputTokens());
    }

    public void check() {
        var error = failure;
        if (error != null) throw error;
    }

    public Result result() {
        return result;
    }

    public ToolExecutionException failure() {
        return failure;
    }

    private @NonNull String json(@NonNull Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return ToolErrors.failure(
                    ToolErrorCode.READER.READER_OUTPUT,
                    "Reader output: the reader result could not be encoded.");
        }
    }

    @Override
    public void close() {
        closed = true;
        document = null;
    }

    public static @NonNull Result finish(
            @NonNull FinishReadArgs value,
            @NonNull WebReadDocument document,
            @NonNull Execution execution) {
        List<String> errors = new ArrayList<>();
        if (!List.of("complete", "partial", "not_found").contains(value.outcome()))
            errors.add("outcome must be complete, partial, or not_found.");
        if (value.answer().isBlank()) errors.add("answer must be nonblank.");
        if (value.answer().length() > MAX_ANSWER_CHARS)
            errors.add(
                    "answer exceeds 4000 characters (received " + value.answer().length() + ").");
        if (value.evidenceIds().size() > MAX_EVIDENCE)
            errors.add(
                    "evidenceIds must contain at most 8 IDs (received "
                            + value.evidenceIds().size()
                            + ").");
        if (value.limitations().size() > 8)
            errors.add(
                    "limitations must contain at most 8 entries (received "
                            + value.limitations().size()
                            + ").");
        if (value.limitations().stream().anyMatch(s -> s.length() > 500))
            errors.add("Each limitations entry must be at most 500 characters.");
        if (!errors.isEmpty())
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: "
                            + String.join(" ", errors)
                            + " Correct all listed fields together. Choose supporting evidence and keep the answer within its scope; exact quotations are attached from evidenceIds. Combine related limitations.");
        var evidence = value.evidenceIds().stream().distinct().map(document::evidence).toList();
        if (value.outcome().equals("complete") && evidence.isEmpty())
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: complete answers need read evidence.");
        String outcome = value.outcome();
        List<String> limitations = new ArrayList<>(value.limitations());
        if (outcome.equals("not_found") && !document.fullyRead()) {
            outcome = "partial";
            limitations.add(
                    "Information was not found in the inspected sections; coverage is incomplete.");
        }
        if (document.truncated()) {
            outcome = "partial";
            limitations.add(
                    "The retrieved document was truncated; relevant information may be missing.");
        }
        return new Result(outcome, value.answer(), evidence, limitations, execution);
    }
}
