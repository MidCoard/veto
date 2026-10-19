package top.focess.veto.builtin.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.builtin.BuiltinJson;
import top.focess.veto.builtin.BundledFrontend;
import top.focess.veto.builtin.monitor.MonitorRecord.ActivationState;

/** Monitor presentation and actions ship with the same plugin as their domain lifecycle. */
public final class MonitorFrontend extends BundledFrontend {
    private final @NonNull MonitorService service;

    /** Creates a frontend backed by the given monitor service. */
    public MonitorFrontend(@NonNull MonitorService service) {
        super("/frontend/monitors.js");
        this.service = service;
    }

    /**
     * Answers paged frontend actions ({@code list}, {@code purpose}, {@code content}, {@code
     * details}).
     */
    public @NonNull JsonValue handle(
            Scope.@NonNull AgentScope scope,
            @NonNull String action,
            JsonValue.@NonNull ObjectValue args)
            throws PluginFailure {
        try {
            var snapshot = service.snapshot(scope.userId(), scope.session());
            var records = snapshot.records();
            int offset = offset(args);
            if (action.equals("list")) {
                var items =
                        records.stream()
                                .skip(offset)
                                .limit(20)
                                .map(MonitorFrontend::summary)
                                .toList();
                return BuiltinJson.value(
                        Map.of(
                                "revision",
                                snapshot.revision(),
                                "items",
                                items,
                                "total",
                                records.size()));
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
                return BuiltinJson.value(
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
                return BuiltinJson.value(
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
                return BuiltinJson.value(Map.of("items", items, "total", events.size()));
            }
            if (!List.of("pause", "resume", "cancel").contains(action))
                throw new IllegalArgumentException();
            service.control(scope.userId(), scope.session(), record.agentId(), record.id(), action);
            return new JsonValue.BooleanValue(true);
        } catch (IllegalArgumentException | ArithmeticException | SecurityException failure) {
            throw new PluginFailure(PluginFailure.Code.INVALID_ARGUMENTS);
        }
    }

    /** The bounded projection shared by controller reads and live publications. */
    static @NonNull Map<String, Object> summary(@NonNull MonitorRecord record) {
        var due = record.dueAt();
        return Map.of(
                "id", record.id(),
                "agentId", record.agentId(),
                "kind", record.kind(),
                "purpose", record.purpose().substring(0, Math.min(2000, record.purpose().length())),
                "purposeTruncated", record.purpose().length() > 2000,
                "state", record.state(),
                "dueAt", due == null ? "" : due.toString(),
                "pending", record.readyEvents().size(),
                "controllable",
                        !record.pending().isEmpty()
                                || record.activationStates().values().stream()
                                        .anyMatch(
                                                value ->
                                                        value.state() == ActivationState.APPENDED
                                                                || value.state()
                                                                        == ActivationState
                                                                                .RUNNING));
    }

    private @NonNull String activation(
            @NonNull MonitorRecord record, MonitorRecord.@NonNull Event event) {
        var activation = record.activationStates().get(event.id());
        return activation == null ? "PENDING" : activation.state().name();
    }

    private int offset(JsonValue.@NonNull ObjectValue args) {
        var value = args.values().get("offset");
        if (value == null) return 0;
        if (!(value instanceof JsonValue.NumberValue number)) throw new IllegalArgumentException();
        int result = number.value().intValueExact();
        if (result < 0) throw new IllegalArgumentException();
        return result;
    }
}
