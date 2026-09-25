package top.focess.veto.builtin.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.plugin.storage.PluginStorage.Document;
import top.focess.veto.api.plugin.storage.PluginStorage.Entry;
import top.focess.veto.api.plugin.storage.PluginStorage.SessionScope;
import top.focess.veto.api.plugin.storage.PluginStorage.Store;

/** Monitor aggregates use session stores; optional payload chunks remain feature-owned. */
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class StoredMonitorRepository implements MonitorRepository {
    private record Stored(Store store, Entry entry) {}

    private final PluginStorage storage;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Store> sessions = new HashMap<>();
    private final Map<String, Stored> known = new HashMap<>();

    public StoredMonitorRepository(PluginStorage storage) {
        this.storage = storage;
    }

    private void discover() {
        sessions.clear();
        String cursor = null;
        do {
            var page = storage.scopes(PluginStorage.Kind.SESSION, cursor, 100);
            for (var scope : page.entries())
                if (scope instanceof SessionScope session)
                    sessions.put(session.sessionId(), storage.session(session));
            cursor = page.cursor();
        } while (cursor != null);
    }

    @Override
    public synchronized List<MonitorEntity> findAll() {
        discover();
        List<MonitorEntity> result = new ArrayList<>();
        for (Store store : sessions.values()) {
            String cursor = null;
            do {
                var page = store.list("monitor/", cursor, 100);
                for (Entry entry : page.entries()) {
                    String id = entry.key().substring("monitor/".length());
                    var value = read(store, entry);
                    known.put(id, new Stored(store, value.entry()));
                    result.add(new MonitorEntity(id, value.payload()));
                }
                cursor = page.cursor();
            } while (cursor != null);
        }
        return result;
    }

    private Store store(String session) {
        Store store = sessions.get(session);
        if (store == null) {
            discover();
            store = sessions.get(session);
        }
        if (store == null)
            throw new SecurityException("Monitor session is unavailable to this plugin");
        return store;
    }

    @Override
    public synchronized MonitorEntity save(MonitorEntity value) {
        String session;
        try {
            var tree = mapper.readTree(value.getPayload());
            if (tree == null || !tree.path("id").asText().equals(value.getId()))
                throw new IllegalArgumentException("Invalid monitor identity");
            session = tree.path("sessionId").asText();
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid monitor document", failure);
        }
        Store store = store(session);
        Stored previous = known.get(value.getId());
        Entry entry = write(store, value, previous == null ? null : previous.entry().revision());
        known.put(value.getId(), new Stored(store, entry));
        if (previous != null) discardChunks(store, previous.entry());
        return value;
    }

    /** Reentrant distribution import never overwrites a stored aggregate. */
    public synchronized boolean importLegacy(MonitorEntity value) {
        try {
            var tree = mapper.readTree(value.getPayload());
            if (tree == null || !tree.path("id").asText().equals(value.getId()))
                throw new IllegalArgumentException("Invalid legacy monitor identity");
            Store store = store(tree.path("sessionId").asText());
            if (store.get("monitor/" + value.getId()).isPresent()) return false;
            Entry entry = write(store, value, null);
            known.put(value.getId(), new Stored(store, entry));
            return true;
        } catch (PluginStorage.Conflict conflict) {
            return false;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot import monitor " + value.getId(), failure);
        }
    }

    private Entry write(Store store, MonitorEntity value, @Nullable String expected) {
        List<Entry> chunks = new ArrayList<>();
        try {
            JsonValue document;
            String payload = value.getPayload();
            if (payload.length() <= 32000) document = new JsonValue.StringValue(payload);
            else {
                String prefix = "payload/" + value.getId() + "/" + UUID.randomUUID() + "/";
                List<JsonValue> keys = new ArrayList<>();
                for (int offset = 0, index = 0;
                        offset < payload.length();
                        offset += 32000, index++) {
                    Entry chunk =
                            store.put(
                                    prefix + index,
                                    null,
                                    new Document(
                                            1,
                                            new JsonValue.StringValue(
                                                    payload.substring(
                                                            offset,
                                                            Math.min(
                                                                    payload.length(),
                                                                    offset + 32000)))));
                    chunks.add(chunk);
                    keys.add(new JsonValue.StringValue(chunk.key()));
                }
                document = new JsonValue.ArrayValue(keys);
            }
            return store.put("monitor/" + value.getId(), expected, new Document(1, document));
        } catch (RuntimeException failure) {
            for (Entry chunk : chunks) {
                try {
                    store.delete(chunk.key(), chunk.revision());
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private record Payload(Entry entry, String payload) {}

    private Payload read(Store store, Entry initial) {
        Entry entry = initial;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return new Payload(entry, payload(store, entry));
            } catch (IllegalStateException failure) {
                Entry latest = store.get(entry.key()).orElseThrow(() -> failure);
                if (latest.revision().equals(entry.revision())) throw failure;
                entry = latest;
            }
        }
        throw new PluginStorage.Conflict();
    }

    private void discardChunks(Store store, Entry entry) {
        if (!(entry.document().value() instanceof JsonValue.ArrayValue parts)) return;
        for (JsonValue part : parts.values()) {
            if (part instanceof JsonValue.StringValue key) {
                try {
                    store.get(key.value())
                            .ifPresent(chunk -> store.delete(key.value(), chunk.revision()));
                } catch (RuntimeException ignored) {
                    // A cleanup race never turns an already committed aggregate into a failed
                    // write.
                    // Session deletion also removes any retained chunks.
                }
            }
        }
    }

    private String payload(Store store, Entry entry) {
        if (entry.document().schemaVersion() != 1)
            throw new IllegalStateException("Unsupported monitor storage version");
        if (entry.document().value() instanceof JsonValue.StringValue value) return value.value();
        if (!(entry.document().value() instanceof JsonValue.ArrayValue parts))
            throw new IllegalStateException("Invalid monitor payload index");
        StringBuilder text = new StringBuilder();
        for (JsonValue part : parts.values()) {
            if (!(part instanceof JsonValue.StringValue key))
                throw new IllegalStateException("Invalid monitor payload chunk key");
            Entry chunk =
                    store.get(key.value())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Missing monitor payload chunk"));
            if (chunk.document().schemaVersion() != 1
                    || !(chunk.document().value() instanceof JsonValue.StringValue value))
                throw new IllegalStateException("Invalid monitor payload chunk");
            text.append(value.value());
        }
        return text.toString();
    }
}
