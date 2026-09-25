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
    /** Durable scope kinds supported by plugin storage. */
    enum Kind {
        /** Data shared by all authorized users and sessions of this plugin installation. */
        APPLICATION,
        /** Data bound to one host-issued user scope. */
        USER,
        /** Data bound to one host-issued session scope. */
        SESSION
    }

    /** Host-issued opaque authorization scope. */
    sealed interface Scope permits UserScope, SessionScope {
        /**
         * Returns the opaque authorization token carried by this scope.
         *
         * @return the opaque token used for host revalidation
         */
        String token();

        /**
         * Returns the authenticated user bound to this scope.
         *
         * @return the authenticated user represented by this scope
         */
        String userId();
    }

    /**
     * Host-issued user scope.
     *
     * @param token opaque host authorization token
     * @param userId authenticated user ID
     */
    record UserScope(String token, String userId) implements Scope {}

    /**
     * Host-issued session scope.
     *
     * @param token opaque host authorization token
     * @param userId authenticated user ID
     * @param sessionId authorized session ID
     */
    record SessionScope(String token, String userId, String sessionId) implements Scope {}

    /**
     * Versioned JSON document stored by a plugin.
     *
     * @param schemaVersion positive plugin-defined schema version
     * @param value document value
     */
    record Document(int schemaVersion, JsonValue value) {
        /** Validates the positive plugin schema version. */
        public Document {
            if (schemaVersion < 1)
                throw new IllegalArgumentException("Schema version must be positive");
        }
    }

    /**
     * Stored key, compare-and-set revision, and document.
     *
     * @param key key within the bound store
     * @param revision opaque current revision required for replacement or deletion
     * @param document stored document
     */
    record Entry(String key, String revision, Document document) {}

    /**
     * Immutable page of storage results.
     *
     * @param <T> page entry type
     * @param entries page entries, copied on construction
     * @param cursor opaque cursor for the next page, or {@code null} at the end
     */
    record Page<T>(List<T> entries, @Nullable String cursor) {
        /** Defensively copies the page entries. */
        public Page {
            entries = List.copyOf(entries);
        }
    }

    /** Indicates that a compare-and-set revision no longer matches current storage. */
    final class Conflict extends RuntimeException {
        /** Creates a conflict without exposing storage implementation details. */
        public Conflict() {
            super("Plugin storage revision conflict");
        }
    }

    /** A single bound scope using compare-and-set revisions for writes and deletion. */
    interface Store {
        /**
         * Returns the current entry for {@code key}.
         *
         * @param key key within this bound store
         * @return the current entry, or an empty value when absent
         */
        Optional<Entry> get(String key);

        /**
         * Lists matching keys in host-defined stable page order.
         *
         * @param prefix key prefix to match
         * @param cursor prior page cursor, or {@code null} for the first page
         * @param limit maximum requested entries
         * @return a page of matching entries
         */
        Page<Entry> list(String prefix, @Nullable String cursor, int limit);

        /**
         * Inserts when revision is null, or replaces only the matching current revision.
         *
         * @param key key within this bound store
         * @param expectedRevision current revision, or {@code null} for insert-if-absent
         * @param document document to store
         * @return the stored entry with its new opaque revision
         */
        Entry put(String key, @Nullable String expectedRevision, Document document);

        /**
         * Deletes only the matching current revision; conflicts do not silently succeed.
         *
         * @param key key within this bound store
         * @param expectedRevision revision that must still be current
         */
        void delete(String key, String expectedRevision);
    }

    /**
     * Returns the store shared across this plugin installation.
     *
     * @return this plugin's application-scoped store
     */
    Store application();

    /**
     * Returns this plugin's store for a revalidated host-issued user scope.
     *
     * @param scope host-issued user scope
     * @return the bound user store
     */
    Store user(UserScope scope);

    /**
     * Returns this plugin's store for a revalidated host-issued session scope.
     *
     * @param scope host-issued session scope
     * @return the bound session store
     */
    Store session(SessionScope scope);

    /**
     * Host-authorized scopes for recovery and background work; never another plugin's namespace.
     *
     * @param kind scope kind to enumerate
     * @param cursor prior page cursor, or {@code null} for the first page
     * @param limit maximum requested scopes
     * @return a page of host-authorized scopes
     */
    Page<Scope> scopes(Kind kind, @Nullable String cursor, int limit);

    /**
     * Returns the session scope for the active authenticated invocation.
     *
     * @return the session scope installed for the current authenticated invocation
     */
    SessionScope currentSession();

    /**
     * Returns the user scope for the active authenticated invocation.
     *
     * @return the user scope installed for the current authenticated invocation
     */
    UserScope currentUser();
}
