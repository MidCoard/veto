package top.focess.veto.group;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import top.focess.veto.session.SessionRecord;

/** Best-effort reading of old tool evidence, never a claim that missing lifecycle data exists. */
public final class LegacyGroupHistory {
    private static final Pattern NODE =
            Pattern.compile("(?m)^- ([^\\s]+) \\[([A-Z]+)] mate=([^\\s]+) skillset=([^\\s]+)");

    private LegacyGroupHistory() {}

    public static @NonNull List<GroupHistoryView> read(@NonNull List<SessionRecord> records) {
        Map<String, SessionRecord> calls = new LinkedHashMap<>();
        Map<String, Builder> current = new LinkedHashMap<>();
        List<Builder> groups = new ArrayList<>();
        for (SessionRecord r : records) {
            if (r.payload().containsKey("restored_from_turn")) continue;
            String callId = text(r.payload(), "call_id");
            String key = r.agentId() + ":" + callId;
            if ("TOOL_CALL".equals(r.type())) {
                calls.put(key, r);
                continue;
            }
            if (!"TOOL_RESPONSE".equals(r.type())
                    || !Boolean.TRUE.equals(r.payload().get("success"))) continue;
            SessionRecord call = calls.get(key);
            if (call == null) continue;
            Map<?, ?> args = call.payload().get("args") instanceof Map<?, ?> m ? m : Map.of();
            String tool = text(call.payload(), "tool_name");
            if ("create_group".equals(tool)) {
                Builder group = new Builder(call, text(args, "task"));
                current.put(r.agentId(), group);
                groups.add(group);
            }
            Builder group = current.get(r.agentId());
            if (group == null) continue;
            String content = text(r.payload(), "content");
            if ("create_node".equals(tool)) {
                String id = text(args, "nodeId");
                List<String> deps =
                        args.get("dependsOn") instanceof List<?> values
                                ? values.stream().map(String::valueOf).toList()
                                : List.of();
                group.nodes.put(
                        id,
                        new GroupHistoryView.Node(
                                id,
                                text(args, "description"),
                                null,
                                text(args, "skillset"),
                                deps,
                                "UNKNOWN",
                                "",
                                0));
            } else if ("inspect_group".equals(tool)) {
                var matcher = NODE.matcher(content);
                while (matcher.find()) {
                    String id = matcher.group(1);
                    String state = matcher.group(2);
                    if (id == null || state == null) continue;
                    var old = group.nodes.get(id);
                    if (old == null) continue;
                    String report = old.report();
                    String following = content.substring(matcher.end()).stripLeading();
                    if (following.startsWith("report: ") || following.startsWith("failure: ")) {
                        int end = following.indexOf('\n');
                        String line = end < 0 ? following : following.substring(0, end);
                        report =
                                line.substring(line.indexOf(": ") + 2)
                                        .replace("\\r", "\r")
                                        .replace("\\n", "\n");
                    }
                    group.nodes.put(
                            id,
                            new GroupHistoryView.Node(
                                    id,
                                    old.description(),
                                    matcher.group(3),
                                    old.skillset(),
                                    old.dependencies(),
                                    "VERIFIED".equals(state) ? "COMPLETED" : state,
                                    report,
                                    old.retries()));
                }
            } else if ("remove_node".equals(tool)) {
                String id = text(args, "nodeId");
                var old = group.nodes.get(id);
                if (old != null)
                    group.nodes.put(
                            id,
                            new GroupHistoryView.Node(
                                    id,
                                    old.description(),
                                    old.mateId(),
                                    old.skillset(),
                                    old.dependencies(),
                                    "STALE",
                                    old.report(),
                                    old.retries()));
            } else if ("disband_group".equals(tool)) {
                group.state = "DISBANDED";
            }
        }
        // Link actual Mate reports independently of the Leader's rewind or its last polling
        // snapshot.
        for (Builder group : groups) {
            for (var node : List.copyOf(group.nodes.values())) {
                String report = node.report();
                boolean assigned = false;
                for (SessionRecord r : records) {
                    if (!r.agentId().equals(node.mateId())
                            || r.timestamp().isBefore(group.start.timestamp())) continue;
                    if ("USER_PROMPT".equals(r.type()))
                        assigned = text(r.payload(), "content").equals(node.description());
                    if (assigned && "ASSISTANT_RESPONSE".equals(r.type()))
                        report = text(r.payload(), "content");
                }
                String state = node.state();
                // A historical final response is evidence of a report, not proof of scheduler
                // success.
                if (!report.isBlank()
                        && !"COMPLETED".equals(state)
                        && !"FAILED".equals(state)
                        && !"STALE".equals(state)) state = "REPORTED";
                group.nodes.put(
                        node.id(),
                        new GroupHistoryView.Node(
                                node.id(),
                                node.description(),
                                node.mateId(),
                                node.skillset(),
                                node.dependencies(),
                                state,
                                report,
                                node.retries()));
            }
        }
        return groups.stream().map(Builder::view).toList();
    }

    private static @NonNull String text(@NonNull Map<?, ?> map, @NonNull String key) {
        return map.get(key) instanceof String s ? s : "";
    }

    private static final class Builder {
        private final @NonNull SessionRecord start;
        private final @NonNull String brief;
        private @NonNull String state = "UNKNOWN";
        private final @NonNull Map<String, GroupHistoryView.Node> nodes = new LinkedHashMap<>();

        Builder(@NonNull SessionRecord start, @NonNull String brief) {
            this.start = start;
            this.brief = brief;
        }

        @NonNull GroupHistoryView view() {
            return new GroupHistoryView(
                    "legacy:" + start.agentId() + ":" + start.turnNumber(),
                    start.agentId(),
                    brief,
                    state,
                    start.timestamp(),
                    List.copyOf(nodes.values()),
                    true,
                    false,
                    List.of());
        }
    }
}
