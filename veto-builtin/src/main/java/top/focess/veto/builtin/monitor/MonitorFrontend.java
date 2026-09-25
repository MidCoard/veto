package top.focess.veto.builtin.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** Monitor presentation and actions ship with the same plugin as their domain lifecycle. */
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class MonitorFrontend {
    private final MonitorService service;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    /** Creates a frontend backed by the given monitor service. */
    public MonitorFrontend(MonitorService service) {
        this.service = service;
    }

    /** Serves the bundled monitors script and routes its actions to {@link #handle}. */
    public FrontendContribution contribution() {
        try (var stream =
                ToolDocs.nonNullClass(MonitorFrontend.class)
                        .getResourceAsStream("/frontend/monitors.js")) {
            if (stream == null) throw new IllegalStateException("Missing monitor frontend");
            return new FrontendContribution(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8), this::handle);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load monitor frontend", failure);
        }
    }

    /**
     * Answers paged frontend actions ({@code list}, {@code purpose}, {@code content}, {@code
     * details}).
     */
    public JsonValue handle(
            FrontendContribution.Scope scope, String action, JsonValue.ObjectValue args)
            throws PluginFailure {
        try {
            var records = service.list(scope.ownerId(), scope.sessionId());
            int offset = offset(args);
            if (action.equals("list")) {
                var items =
                        records.stream()
                                .skip(offset)
                                .limit(20)
                                .map(
                                        record ->
                                                Map.of(
                                                        "id",
                                                        record.id(),
                                                        "agentId",
                                                        record.agentId(),
                                                        "kind",
                                                        record.kind(),
                                                        "purpose",
                                                        record.purpose()
                                                                .substring(
                                                                        0,
                                                                        Math.min(
                                                                                2000,
                                                                                record.purpose()
                                                                                        .length())),
                                                        "purposeTruncated",
                                                        record.purpose().length() > 2000,
                                                        "state",
                                                        record.state(),
                                                        "dueAt",
                                                        dueAt(record),
                                                        "pending",
                                                        record.readyEvents().size(),
                                                        "controllable",
                                                        !record.pending().isEmpty()
                                                                || record
                                                                        .activationStates()
                                                                        .values()
                                                                        .stream()
                                                                        .anyMatch(
                                                                                value ->
                                                                                        value
                                                                                                                .state()
                                                                                                        == MonitorRecord
                                                                                                                .ActivationState
                                                                                                                .APPENDED
                                                                                                || value
                                                                                                                .state()
                                                                                                        == MonitorRecord
                                                                                                                .ActivationState
                                                                                                                .RUNNING)))
                                .toList();
                return json(Map.of("items", items, "total", records.size()));
            }
            if (!(args.values().get("id") instanceof JsonValue.StringValue id))
                throw new IllegalArgumentException();
            var record =
                    records.stream()
                            .filter(row -> row.id().equals(id.value()))
                            .findFirst()
                            .orElseThrow(IllegalArgumentException::new);
            if (action.equals("purpose")) {
                String content = record.purpose();
                if (offset > content.length()) throw new IllegalArgumentException();
                return json(
                        Map.of(
                                "content",
                                content.substring(
                                        offset, Math.min(content.length(), offset + 4000)),
                                "total",
                                content.length()));
            }
            if (action.equals("content")) {
                if (!(args.values().get("eventId") instanceof JsonValue.StringValue eventId))
                    throw new IllegalArgumentException();
                var events = new ArrayList<>(record.deliveredEvents());
                events.addAll(record.pending());
                var event =
                        events.stream()
                                .filter(value -> value.id().equals(eventId.value()))
                                .findFirst()
                                .orElseThrow(IllegalArgumentException::new);
                String content = event.content();
                if (offset > content.length()) throw new IllegalArgumentException();
                return json(
                        Map.of(
                                "content",
                                content.substring(
                                        offset, Math.min(content.length(), offset + 4000)),
                                "total",
                                content.length()));
            }
            if (action.equals("details")) {
                var events = new ArrayList<>(record.deliveredEvents());
                events.addAll(record.pending());
                var items =
                        events.stream()
                                .skip(offset)
                                .limit(10)
                                .map(
                                        event ->
                                                Map.of(
                                                        "id",
                                                        event.id(),
                                                        "content",
                                                        event.content()
                                                                .substring(
                                                                        0,
                                                                        Math.min(
                                                                                4000,
                                                                                event.content()
                                                                                        .length())),
                                                        "truncated",
                                                        event.content().length() > 4000,
                                                        "state",
                                                        activation(record, event)))
                                .toList();
                return json(Map.of("items", items, "total", events.size()));
            }
            if (!List.of("pause", "resume", "cancel").contains(action))
                throw new IllegalArgumentException();
            service.control(
                    scope.ownerId(), scope.sessionId(), record.agentId(), record.id(), action);
            return new JsonValue.BooleanValue(true);
        } catch (IllegalArgumentException | ArithmeticException | SecurityException failure) {
            throw new PluginFailure(PluginFailure.Code.INVALID_ARGUMENTS);
        }
    }

    private String dueAt(MonitorRecord record) {
        var due = record.dueAt();
        return due == null ? "" : due.toString();
    }

    private String activation(MonitorRecord record, MonitorRecord.Event event) {
        var activation = record.activationStates().get(event.id());
        return activation == null ? "PENDING" : activation.state().name();
    }

    private int offset(JsonValue.ObjectValue args) {
        var value = args.values().get("offset");
        if (value == null) return 0;
        if (!(value instanceof JsonValue.NumberValue number)) throw new IllegalArgumentException();
        int result = number.value().intValueExact();
        if (result < 0) throw new IllegalArgumentException();
        return result;
    }

    private JsonValue json(Object value) {
        return JsonValues.from(mapper.valueToTree(value));
    }
}
