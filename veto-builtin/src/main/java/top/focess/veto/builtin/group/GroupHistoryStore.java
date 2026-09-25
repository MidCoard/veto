package top.focess.veto.builtin.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.api.plugin.storage.PluginStorage;

/** Append-only snapshot chain with bounded chunks and a CAS head; feature-owned schema. */
public final class GroupHistoryStore {
    private final @NonNull PluginStorage storage;
    private final @NonNull Map<String, PluginStorage.SessionScope> scopes =
            new ConcurrentHashMap<>();

    public void scope(PluginStorage.@NonNull SessionScope scope) {
        scopes.put(scope.sessionId(), scope);
    }

    private final @NonNull ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public GroupHistoryStore(@NonNull PluginStorage storage) {
        this.storage = storage;
    }

    public PluginStorage.@NonNull Store session(@NonNull String id) {
        var cached = scopes.get(id);
        if (cached != null) return storage.session(cached);
        String cursor = null;
        do {
            var page = storage.scopes(PluginStorage.Kind.SESSION, cursor, 200);
            for (var scope : page.entries())
                if (scope instanceof PluginStorage.SessionScope session
                        && session.sessionId().equals(id)) {
                    scope(session);
                    return storage.session(session);
                }
            cursor = page.cursor();
        } while (cursor != null);
        throw new SecurityException("Group session unavailable");
    }

    private static int chunkCount(JsonValue value) {
        if (!(value instanceof JsonValue.NumberValue number))
            throw new IllegalStateException("Missing chunk count");
        return number.value().intValueExact();
    }

    private record ProfileValue(
            @NonNull String name,
            @NonNull String description,
            @NonNull String label,
            @NonNull Set<String> tools,
            String tier,
            String prompt,
            JsonNode data,
            @NonNull Map<String, String> metadata) {}

    public void profile(
            @NonNull String session, @NonNull String agent, @NonNull AgentProfile profile) {
        var store = session(session);
        String key = "group-profile/" + agent;
        var old = store.get(key).orElse(null);
        try {
            var prompt = profile.prompt();
            var value =
                    new ProfileValue(
                            profile.name(),
                            profile.description(),
                            profile.label(),
                            profile.tools(),
                            profile.tier(),
                            prompt == null ? null : prompt.resource(),
                            prompt == null
                                    ? null
                                    : mapper.valueToTree(JsonValues.toMap(prompt.data())),
                            profile.metadata());
            String payload = mapper.writeValueAsString(value);
            String blob = "group-profile-data/" + UUID.randomUUID();
            int count = Math.max(1, (payload.length() + 15999) / 16000);
            List<PluginStorage.Entry> chunks = new ArrayList<>();
            try {
                for (int i = 0; i < count; i++)
                    chunks.add(
                            store.put(
                                    blob + "/" + i,
                                    null,
                                    new PluginStorage.Document(
                                            1,
                                            new JsonValue.StringValue(
                                                    payload.substring(
                                                            i * 16000,
                                                            Math.min(
                                                                    payload.length(),
                                                                    (i + 1) * 16000))))));
                store.put(
                        key,
                        old == null ? null : old.revision(),
                        new PluginStorage.Document(
                                2,
                                new JsonValue.ObjectValue(
                                        Map.of(
                                                "blob",
                                                new JsonValue.StringValue(blob),
                                                "chunks",
                                                new JsonValue.NumberValue(
                                                        BigDecimal.valueOf(count))))));
            } catch (RuntimeException error) {
                for (var chunk : chunks) store.delete(chunk.key(), chunk.revision());
                throw error;
            }
            // Older immutable blobs remain valid for readers until session-scope cleanup.
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public AgentProfile profile(@NonNull String session, @NonNull String agent) {
        var entry = session(session).get("group-profile/" + agent).orElse(null);
        if (entry == null) return null;
        try {
            String payload;
            if (entry.document().schemaVersion() == 1) payload = string(entry.document().value());
            else {
                var fields = (JsonValue.ObjectValue) entry.document().value();
                int count = chunkCount(fields.values().get("chunks"));
                String blob = string(fields.values().get("blob"));
                StringBuilder content = new StringBuilder();
                for (int i = 0; i < count; i++)
                    content.append(
                            string(
                                    session(session)
                                            .get(blob + "/" + i)
                                            .orElseThrow()
                                            .document()
                                            .value()));
                payload = content.toString();
            }
            var value = mapper.readValue(payload, ToolDocs.nonNullClass(ProfileValue.class));
            return new AgentProfile(
                    value.name(),
                    value.description(),
                    value.label(),
                    value.tools(),
                    value.tier(),
                    value.prompt() == null
                            ? null
                            : new AgentProfile.Prompt(
                                    value.prompt(),
                                    value.data() == null
                                            ? new JsonValue.ObjectValue(Map.of())
                                            : (JsonValue.ObjectValue)
                                                    JsonValues.from(value.data())),
                    value.metadata());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public void save(@NonNull Group group) {
        var session = group.sessionId();
        if (session == null) return;
        append(
                session.toString(),
                new GroupHistoryView(
                        group.groupId().toString(),
                        group.leaderId(),
                        group.contextBrief(),
                        group.state().name(),
                        group.createdAt(),
                        GroupHistoryView.nodes(group),
                        false,
                        true,
                        List.of(),
                        group.mates()),
                Instant.now(),
                null);
    }

    public boolean append(
            @NonNull String sessionId,
            @NonNull GroupHistoryView view,
            @NonNull Instant at,
            String importId) {
        var store = session(sessionId);
        String headKey = "group/" + view.id();
        for (int attempt = 0; attempt < 8; attempt++) {
            var head = store.get(headKey).orElse(null);
            String previous = head == null ? null : string(head.document().value());
            if (importId != null && imported(store, previous, importId)) return false;
            String event = UUID.randomUUID().toString();
            List<PluginStorage.Entry> created = new ArrayList<>();
            try {
                String payload = mapper.writeValueAsString(view);
                int chunks = (payload.length() + 15999) / 16000;
                for (int i = 0; i < chunks; i++)
                    created.add(
                            store.put(
                                    "group-event/" + event + "/" + i,
                                    null,
                                    new PluginStorage.Document(
                                            1,
                                            new JsonValue.StringValue(
                                                    payload.substring(
                                                            i * 16000,
                                                            Math.min(
                                                                    payload.length(),
                                                                    (i + 1) * 16000))))));
                var header =
                        mapper.createObjectNode().put("chunks", chunks).put("at", at.toString());
                if (previous != null) header.put("previous", previous);
                if (importId != null) header.put("sourceId", importId);
                created.add(
                        store.put(
                                "group-event/" + event,
                                null,
                                new PluginStorage.Document(1, JsonValues.from(header))));
                store.put(
                        headKey,
                        head == null ? null : head.revision(),
                        new PluginStorage.Document(1, new JsonValue.StringValue(event)));
                return true;
            } catch (PluginStorage.Conflict conflict) {
                // Chunks not referenced by a committed head are disposable.
                var now = store.get(headKey).orElse(null);
                if (now != null && event.equals(string(now.document().value()))) return true;
                for (var row : created) store.delete(row.key(), row.revision());
            } catch (IOException error) {
                throw new IllegalStateException("Cannot serialize group history", error);
            }
        }
        throw new PluginStorage.Conflict();
    }

    public @NonNull List<GroupHistoryView> latestSnapshots(@NonNull String sessionId) {
        return load(sessionId, new GroupRegistry());
    }

    // latest is assigned only inside the page loop; the NullnessChecker needs the guard to refine
    // it.
    @SuppressWarnings("ConstantValue")
    public @NonNull List<GroupHistoryView> load(
            @NonNull String sessionId, @NonNull GroupRegistry registry) {
        var store = session(sessionId);
        List<GroupHistoryView> result = new ArrayList<>();
        String cursor = null;
        do {
            var page = store.list("group/", cursor, 200);
            for (var head : page.entries()) {
                String event = string(head.document().value());
                GroupHistoryView latest = null;
                List<GroupHistoryView.Change> changes = new ArrayList<>();
                var visited = new HashSet<String>();
                while (event != null) {
                    if (!visited.add(event))
                        throw new IllegalStateException("Cyclic group history");
                    var header = store.get("group-event/" + event).orElseThrow();
                    var fields = (JsonValue.ObjectValue) header.document().value();
                    int chunks = chunkCount(fields.values().get("chunks"));
                    var payload = new StringBuilder();
                    for (int i = 0; i < chunks; i++)
                        payload.append(
                                string(
                                        store.get("group-event/" + event + "/" + i)
                                                .orElseThrow()
                                                .document()
                                                .value()));
                    try {
                        var view =
                                mapper.readValue(
                                        payload.toString(),
                                        ToolDocs.nonNullClass(GroupHistoryView.class));
                        if (latest == null) latest = view;
                        changes.add(
                                new GroupHistoryView.Change(
                                        Instant.parse(string(fields.values().get("at"))),
                                        view.state(),
                                        view.nodes()));
                    } catch (IOException error) {
                        throw new IllegalStateException("Cannot read group history", error);
                    }
                    var prior = fields.values().get("previous");
                    event = prior == null ? null : string(prior);
                }
                if (latest != null) {
                    Collections.reverse(changes);
                    result.add(
                            new GroupHistoryView(
                                            latest.id(),
                                            latest.leaderId(),
                                            latest.brief(),
                                            latest.state(),
                                            latest.createdAt(),
                                            latest.nodes(),
                                            latest.historical(),
                                            isLive(latest, registry),
                                            List.copyOf(changes),
                                            latest.mates())
                                    .withoutRuntime());
                }
            }
            cursor = page.cursor();
        } while (cursor != null);
        return List.copyOf(result);
    }

    private static boolean isLive(@NonNull GroupHistoryView view, @NonNull GroupRegistry registry) {
        if (view.historical()) return false;
        try {
            return registry.get(UUID.fromString(view.id())) != null;
        } catch (IllegalArgumentException invalidHistoricalId) {
            return false;
        }
    }

    private boolean imported(PluginStorage.@NonNull Store store, String event, @NonNull String id) {
        var visited = new HashSet<String>();
        while (event != null) {
            if (!visited.add(event)) throw new IllegalStateException("Cyclic group history");
            var entry = store.get("group-event/" + event).orElseThrow();
            var fields = (JsonValue.ObjectValue) entry.document().value();
            var source = fields.values().get("sourceId");
            if (source != null && id.equals(string(source))) return true;
            var previous = fields.values().get("previous");
            event = previous == null ? null : string(previous);
        }
        return false;
    }

    private static @NonNull String string(JsonValue value) {
        if (value instanceof JsonValue.StringValue text) return text.value();
        throw new IllegalStateException("Invalid group storage record");
    }
}
