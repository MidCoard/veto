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
 * Durable namespace bound to the current plugin identity.
 *
 * <p>Application, user, and session stores cannot access another plugin's namespace. User and
 * session scopes are host-issued grants rather than caller-chosen identity strings, and every
 * operation revalidates plugin admission, scope existence, ownership, and authorization. Retained
 * stores and scopes may therefore become unusable after stop, deselection, or permanent scope
 * deletion. Plugin disable or uninstall retains data by default; a future management purge is not
 * part of this API guarantee.
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

    /** A single bound scope using compare-and-set revisions for writes and deletion. */
    interface Store {
        Optional<Entry> get(String key);

        Page<Entry> list(String prefix, @Nullable String cursor, int limit);

        /** Inserts when revision is null, or replaces only the matching current revision. */
        Entry put(String key, @Nullable String expectedRevision, Document document);

        /** Deletes only the matching current revision; conflicts do not silently succeed. */
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

    /** Only available while the host has installed an authenticated invocation context. */
    UserScope currentUser();
}
