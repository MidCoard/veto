package top.focess.veto.builtin.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** Bounded wire pages; complete durable history remains readable through text chunks. */
public final class GroupFrontend {
    private final @NonNull GroupRuntime runtime;
    private final @NonNull ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public GroupFrontend(@NonNull GroupRuntime runtime) {
        this.runtime = runtime;
    }

    public @NonNull FrontendContribution contribution() {
        try (var stream =
                ToolDocs.nonNullClass(GroupFrontend.class)
                        .getResourceAsStream("/frontend/groups.js")) {
            if (stream == null) throw new IllegalStateException("Missing group frontend");
            return new FrontendContribution(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8), this::handle);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public @NonNull JsonValue handle(
            FrontendContribution.@NonNull Scope scope,
            @NonNull String action,
            JsonValue.@NonNull ObjectValue args)
            throws PluginFailure {
        try {
            var groups =
                    runtime.history().load(scope.sessionId(), runtime.registry()).stream()
                            .sorted(
                                    Comparator.comparing(GroupHistoryView::createdAt)
                                            .thenComparing(GroupHistoryView::id))
                            .toList();
            int offset = number(args, "offset", 0),
                    limit = Math.min(20, Math.max(1, number(args, "limit", 20)));
            if (action.equals("list")) {
                var items =
                        groups.stream()
                                .skip(offset)
                                .limit(limit)
                                .<Map<String, Object>>map(
                                        group -> {
                                            Map<String, Object> row = new LinkedHashMap<>();
                                            row.put("id", group.id());
                                            row.put("leaderId", group.leaderId());
                                            row.put("state", group.state());
                                            row.put("live", group.live());
                                            row.put("historical", group.historical());
                                            row.put("createdAt", group.createdAt());
                                            row.put("briefLength", group.brief().length());
                                            row.put("nodeCount", group.nodes().size());
                                            row.put("changeCount", group.changes().size());
                                            return row;
                                        })
                                .toList();
                return json(
                        Map.of(
                                "items",
                                items,
                                "total",
                                groups.size(),
                                "totalNodeCount",
                                groups.stream().mapToInt(group -> group.nodes().size()).sum()));
            }
            String id = text(args, "groupId");
            var group =
                    groups.stream()
                            .filter(value -> value.id().equals(id))
                            .findFirst()
                            .orElseThrow();
            var nodes = group.nodes();
            if (action.equals("changes")) {
                List<Object> rows = new ArrayList<>();
                for (int i = offset; i < Math.min(group.changes().size(), offset + limit); i++) {
                    var change = group.changes().get(i);
                    rows.add(
                            Map.of(
                                    "index",
                                    i,
                                    "at",
                                    change.at(),
                                    "state",
                                    change.state(),
                                    "nodeCount",
                                    change.nodes().size()));
                }
                return json(Map.of("items", rows, "total", group.changes().size()));
            }
            if (action.equals("changeNodes") || args.values().containsKey("changeIndex"))
                nodes = group.changes().get(number(args, "changeIndex", -1)).nodes();
            if (action.equals("nodes") || action.equals("changeNodes"))
                return json(
                        Map.of(
                                "items",
                                nodes.stream()
                                        .skip(offset)
                                        .limit(limit)
                                        .map(GroupFrontend::node)
                                        .toList(),
                                "total",
                                nodes.size()));
            if (action.equals("text")) {
                String field = text(args, "field"), content;
                if (field.equals("brief")) content = group.brief();
                else {
                    String nodeId = text(args, "nodeId");
                    var selected =
                            nodes.stream()
                                    .filter(value -> value.id().equals(nodeId))
                                    .findFirst()
                                    .orElseThrow();
                    content =
                            switch (field) {
                                case "description" -> selected.description();
                                case "report" -> selected.report();
                                default -> throw new IllegalArgumentException("Unknown text field");
                            };
                }
                if (offset > content.length()) throw new IllegalArgumentException("Invalid offset");
                int end =
                        Math.min(
                                content.length(),
                                offset + Math.min(8192, Math.max(2, number(args, "limit", 8192))));
                if (end < content.length()
                        && end > offset
                        && Character.isHighSurrogate(content.charAt(end - 1))) end--;
                return json(
                        Map.of(
                                "text",
                                content.substring(offset, end),
                                "total",
                                content.length(),
                                "nextOffset",
                                end));
            }
            throw new IllegalArgumentException("Unknown group action");
        } catch (IllegalArgumentException
                | IndexOutOfBoundsException
                | java.util.NoSuchElementException error) {
            throw new PluginFailure(PluginFailure.Code.INVALID_ARGUMENTS);
        } catch (RuntimeException error) {
            throw new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
        }
    }

    private static @NonNull Map<String, Object> node(GroupHistoryView.@NonNull Node node) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", node.id());
        row.put("state", node.state());
        var mateId = node.mateId();
        if (mateId != null) row.put("mateId", mateId);
        row.put("dependsOn", node.dependencies());
        row.put("retries", node.retries());
        var requestId = node.requestId();
        if (requestId != null) row.put("requestId", requestId);
        var dispatchId = node.dispatchId();
        if (dispatchId != null) row.put("dispatchId", dispatchId);
        row.put("descriptionLength", node.description().length());
        row.put("reportLength", node.report().length());
        return row;
    }

    private @NonNull JsonValue json(@NonNull Object value) {
        return JsonValues.from(mapper.valueToTree(value));
    }

    private static int number(
            JsonValue.@NonNull ObjectValue args, @NonNull String key, int fallback) {
        var value = args.values().get(key);
        int result =
                value instanceof JsonValue.NumberValue number
                        ? number.value().intValueExact()
                        : fallback;
        if (result < 0) throw new IllegalArgumentException("Invalid page position");
        return result;
    }

    private static @NonNull String text(JsonValue.@NonNull ObjectValue args, @NonNull String key) {
        if (args.values().get(key) instanceof JsonValue.StringValue text) return text.value();
        throw new IllegalArgumentException("Missing " + key);
    }
}
