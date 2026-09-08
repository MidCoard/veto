package top.focess.veto.agent.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.capability.WebDocumentCapability;
import top.focess.veto.agent.capability.WebReadCapability;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolExecutionException;

/** Document state and effect boundary owned by exactly one reader AgentRunner. */
final class WebReadSession implements WebDocumentCapability, AutoCloseable {
    private static final int OUTLINE_ENTRIES = 24;
    private final @NonNull ObjectMapper mapper;
    private final @NonNull WebReadCapability resource;
    private final @NonNull String agentId;
    private final @NonNull ToolCallContext parent;
    private final @NonNull UUID sessionId;
    private final long deadline;
    private final @NonNull Supplier<WebReader.@NonNull Execution> execution;
    private volatile boolean closed;
    private int observationBudget;
    private WebReadDocument document;
    private volatile WebReader.Result result;
    private volatile ToolExecutionException failure;

    WebReadSession(
            @NonNull ObjectMapper mapper,
            @NonNull WebReadCapability resource,
            @NonNull String agentId,
            @NonNull ToolCallContext parent,
            @NonNull UUID sessionId,
            long deadline,
            @NonNull Supplier<WebReader.@NonNull Execution> execution) {
        this.mapper = mapper;
        this.resource = resource;
        this.agentId = agentId;
        this.parent = parent;
        this.sessionId = sessionId;
        this.deadline = deadline;
        this.execution = execution;
    }

    private void authorize(@NonNull String operation) {
        WebReader.checkDeadline(deadline);
        var context = CapabilityAccess.require(ToolCapability.NETWORK_EGRESS, operation);
        if (closed
                || !agentId.equals(context.agentId())
                || !parent.userId().equals(context.userId())
                || !Objects.equals(parent.owner(), context.owner())
                || !sessionId.equals(context.sessionId()))
            throw new SecurityException("This document belongs to another reader invocation.");
        ToolExecutionException failed = failure;
        if (failed != null) throw failed;
    }

    @Override
    public @NonNull String fetchPage() {
        authorize("fetch_page");
        try {
            WebReadDocument current = document;
            if (current == null) {
                current = new WebReadDocument(resource.fetch(deadline));
                document = current;
            }
            var outline = current.outline();
            return json(
                    Map.of(
                            "outline",
                            outline.stream().limit(OUTLINE_ENTRIES).toList(),
                            "segmentCount",
                            outline.size(),
                            "truncated",
                            current.truncated()));
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
        if (observation.getBytes(StandardCharsets.UTF_8).length > observationBudget)
            return ToolErrors.failure(
                    "READER_OBSERVATION",
                    "These sections exceed the reading budget. Read fewer IDs per call.");
        current.recordInspection(ids);
        return observation;
    }

    @Override
    public @NonNull String finish(WebReader.@NonNull Finish value) {
        authorize("finish_read");
        WebReader.Result completed = WebReader.finish(value, document(), execution.get());
        String content = json(completed);
        WebReader.checkDeadline(deadline);
        result = completed;
        return content;
    }

    private @NonNull WebReadDocument document() {
        WebReadDocument current = document;
        if (current == null) return ToolErrors.failure("READER_DOCUMENT", "Fetch the page first.");
        return current;
    }

    void setObservationBudget(int bytes) {
        observationBudget = Math.max(0, bytes);
    }

    WebReader.Result result() {
        return result;
    }

    ToolExecutionException failure() {
        return failure;
    }

    private @NonNull String json(@NonNull Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return ToolErrors.failure("READER_OUTPUT", "Could not encode reader result.");
        }
    }

    @Override
    public void close() {
        closed = true;
        document = null;
    }
}
