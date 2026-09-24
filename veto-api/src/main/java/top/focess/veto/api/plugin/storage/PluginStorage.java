package top.focess.veto.api.plugin.storage;

import java.util.List;
import java.util.Optional;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.contract.JsonValue;

/**
 * Durable plugin namespace. Scopes are issued and revalidated by the host, not identity strings.
 */
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public interface PluginStorage {
    enum Kind {
        APPLICATION,
        USER,
        SESSION
    }

    sealed interface Scope permits UserScope, SessionScope {
        String token();

        String userId();
    }

    record UserScope(String token, String userId) implements Scope {}

    record SessionScope(String token, String userId, String sessionId) implements Scope {}

    record Document(int schemaVersion, JsonValue value) {
        public Document {
            if (schemaVersion < 1)
                throw new IllegalArgumentException("Schema version must be positive");
        }
    }

    record Entry(String key, String revision, Document document) {}

    record Page<T>(List<T> entries, @Nullable String cursor) {
        public Page {
            entries = List.copyOf(entries);
        }
    }

    final class Conflict extends RuntimeException {
        public Conflict() {
            super("Plugin storage revision conflict");
        }
    }

    interface Store {
        Optional<Entry> get(String key);

        Page<Entry> list(String prefix, @Nullable String cursor, int limit);

        Entry put(String key, @Nullable String expectedRevision, Document document);

        void delete(String key, String expectedRevision);
    }

    Store application();

    Store user(UserScope scope);

    Store session(SessionScope scope);

    /**
     * Host-authorized scopes for recovery and background work; never another plugin's namespace.
     */
    Page<Scope> scopes(Kind kind, @Nullable String cursor, int limit);

    /** Only available while the host has installed an authenticated invocation context. */
    SessionScope currentSession();

    UserScope currentUser();
}
