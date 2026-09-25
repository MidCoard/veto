package top.focess.veto.builtin.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.builtin.process.BackgroundTasks.Scope;

/** Scoped process cards and bounded, explicitly paged access to retained output. */
public final class TasksFrontend {
    private final @NonNull BackgroundTasks tasks;
    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    /** Creates a frontend backed by the given task registry. */
    public TasksFrontend(@NonNull BackgroundTasks tasks) {
        this.tasks = tasks;
    }

    /** Serves the bundled tasks script and routes its actions to {@link #handle}. */
    public @NonNull FrontendContribution contribution() {
        try (var source =
                ToolDocs.nonNullClass(TasksFrontend.class)
                        .getResourceAsStream("/frontend/tasks.js")) {
            if (source == null) throw new IllegalStateException("Missing tasks frontend");
            return new FrontendContribution(
                    new String(source.readAllBytes(), StandardCharsets.UTF_8), this::handle);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load tasks frontend", failure);
        }
    }

    /**
     * Answers paged frontend actions ({@code list}, {@code output}, {@code detail}, {@code
     * stopOrRemove}).
     */
    public @NonNull JsonValue handle(
            FrontendContribution.@NonNull Scope scope,
            @NonNull String action,
            JsonValue.@NonNull ObjectValue arguments)
            throws PluginFailure {
        try {
            String agent = scope.agentId();
            var owned = new BackgroundTasks.Scope(scope.ownerId(), scope.sessionId(), agent);
            int offset = Math.max(0, number(arguments, "offset", 0));
            if (action.equals("list")) {
                var values = tasks.list(owned);
                int limit = Math.max(1, Math.min(20, number(arguments, "limit", 20)));
                int from = Math.min(offset, values.size()),
                        end = Math.min(values.size(), from + limit);
                Map<String, @Nullable Object> result = new LinkedHashMap<>();
                result.put(
                        "items",
                        values.subList(from, end).stream().map(task -> row(owned, task)).toList());
                result.put("total", values.size());
                result.put("nextOffset", end < values.size() ? end : null);
                return json(result);
            }
            String id = text(arguments, "taskId");
            UUID instance = UUID.fromString(text(arguments, "taskInstanceId"));
            int limit = Math.max(2, Math.min(8192, number(arguments, "limit", 8192)));
            if (action.equals("output"))
                return json(tasks.outputPage(owned, id, instance, offset, limit));
            if (action.equals("detail")) {
                var task = tasks.exact(owned, id, instance);
                String value =
                        switch (text(arguments, "field")) {
                            case "command" -> task.command();
                            case "cwd" -> task.cwd();
                            default -> throw new IllegalArgumentException("Unknown detail field");
                        };
                int from = Math.min(offset, value.length()),
                        end = Math.min(value.length(), from + limit);
                if (end < value.length()
                        && end > from
                        && Character.isHighSurrogate(value.charAt(end - 1))) end--;
                return json(
                        new BackgroundTasks.TextPage(
                                value.substring(from, end),
                                value.length(),
                                end < value.length() ? end : null));
            }
            if (action.equals("stopOrRemove")) {
                var result = tasks.stopOrRemove(owned, id, instance);
                return json(Map.of("status", result.status(), "task", row(owned, result.task())));
            }
            throw new IllegalArgumentException("Unknown tasks action");
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new PluginFailure(PluginFailure.Code.INVALID_ARGUMENTS);
        }
    }

    private @NonNull Map<String, @Nullable Object> row(
            @NonNull Scope scope, @NonNull TaskInfo task) {
        Map<String, @Nullable Object> result = new LinkedHashMap<>();
        result.put("taskId", task.taskId());
        result.put("taskInstanceId", task.taskInstanceId().toString());
        result.put("command", prefix(task.command(), 1024));
        result.put("commandTruncated", task.command().length() > 1024);
        result.put("cwd", prefix(task.cwd(), 1024));
        result.put("cwdTruncated", task.cwd().length() > 1024);
        result.put("pid", task.pid());
        result.put("alive", task.alive());
        result.put("exitCode", task.exitCode());
        result.put("startedAt", task.startedAt().toString());
        var finished = task.finishedAt();
        result.put("finishedAt", finished == null ? null : finished.toString());
        result.put("uptimeSeconds", task.uptimeSeconds());
        result.put("recentOutput", prefix(tasks.output(scope, task.taskId(), 20).orElse(""), 512));
        return result;
    }

    private static @NonNull String prefix(@NonNull String value, int limit) {
        int end = Math.min(limit, value.length());
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1)))
            end--;
        return value.substring(0, end);
    }

    private static @NonNull String text(JsonValue.@NonNull ObjectValue args, @NonNull String key) {
        if (args.values().get(key) instanceof JsonValue.StringValue value) return value.value();
        throw new IllegalArgumentException("Missing text argument");
    }

    private static int number(
            JsonValue.@NonNull ObjectValue args, @NonNull String key, int fallback) {
        var value = args.values().get(key);
        if (value == null) return fallback;
        if (value instanceof JsonValue.NumberValue number) return number.value().intValueExact();
        throw new IllegalArgumentException("Invalid page argument");
    }

    private @NonNull JsonValue json(@NonNull Object value) {
        return JsonValues.from(mapper.valueToTree(value));
    }
}
