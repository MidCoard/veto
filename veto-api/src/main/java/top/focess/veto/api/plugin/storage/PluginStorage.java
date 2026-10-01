package top.focess.veto.api.plugin.storage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;

/**
 * Durable namespace bound to the current plugin identity.
 *
 * <p>Application, user, and session stores cannot access another plugin's namespace. User and
 * session stores require host-issued grants rather than caller-chosen identity strings, and every
 * operation revalidates plugin admission, scope existence, ownership, and authorization. Retained
 * stores and scopes may therefore become unusable after stop, deselection, or permanent scope
 * deletion. Plugin disable or uninstall retains data by default; a future management purge is not
 * part of this API guarantee.
 */
public interface PluginStorage {
    /**
     * Host-issued storage authorization paired with the shared scope identity.
     *
     * <p>The scope's owner is the immutable storage user ID, not a login name. The identity alone
     * grants no access; every operation validates this token against its issuing plugin binding and
     * the current scope incarnation. Constructing a grant does not issue a token or authorize
     * storage. Only user and session scopes have stores; application storage is already bound to
     * the plugin installation.
     *
     * @param <S> shared user or session scope type
     * @param token opaque host authorization token
     * @param scope shared identity authorized by this token
     */
    record Grant<S extends @NonNull Scope>(@NonNull String token, @NonNull S scope) {
        /** Validates that this grant names a supported storage scope. */
        public Grant {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(scope, "scope");
            if (!(scope instanceof Scope.UserScope || scope instanceof Scope.SessionScope))
                throw new IllegalArgumentException(
                        "Storage grants require a user or session scope");
        }
    }

    /**
     * Versioned JSON document stored by a plugin.
     *
     * @param schemaVersion positive plugin-defined schema version
     * @param value document value
     */
    record Document(int schemaVersion, @NonNull JsonValue value) {
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
    record Entry(@NonNull String key, @NonNull String revision, @NonNull Document document) {}

    /**
     * Immutable page of storage results.
     *
     * @param <T> page entry type
     * @param entries page entries, copied on construction
     * @param cursor opaque cursor for the next page, or {@code null} at the end
     */
    record Page<T extends @NonNull Object>(@NonNull List<@NonNull T> entries, String cursor) {
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
        @NonNull Optional<@NonNull Entry> get(@NonNull String key);

        /**
         * Lists matching keys in host-defined stable page order.
         *
         * @param prefix key prefix to match
         * @param cursor prior page cursor, or {@code null} for the first page
         * @param limit maximum requested entries
         * @return a page of matching entries
         */
        @NonNull Page<@NonNull Entry> list(@NonNull String prefix, String cursor, int limit);

        /**
         * Inserts when revision is null, or replaces only the matching current revision.
         *
         * @param key key within this bound store
         * @param expectedRevision current revision, or {@code null} for insert-if-absent
         * @param document document to store
         * @return the stored entry with its new opaque revision
         */
        @NonNull Entry put(
                @NonNull String key, String expectedRevision, @NonNull Document document);

        /**
         * Deletes only the matching current revision; conflicts do not silently succeed.
         *
         * @param key key within this bound store
         * @param expectedRevision revision that must still be current
         */
        void delete(@NonNull String key, @NonNull String expectedRevision);
    }

    /**
     * Returns the store shared across this plugin installation.
     *
     * @return this plugin's application-scoped store
     */
    @NonNull Store application();

    /**
     * Returns this plugin's store for a revalidated host-issued user grant.
     *
     * @param grant host-issued user grant
     * @return the bound user store
     */
    @NonNull Store user(@NonNull Grant<Scope.@NonNull UserScope> grant);

    /**
     * Returns this plugin's store for a revalidated host-issued session grant.
     *
     * @param grant host-issued session grant
     * @return the bound session store
     */
    @NonNull Store session(@NonNull Grant<Scope.@NonNull SessionScope> grant);

    /**
     * Host-authorized grants for recovery and background work; never another plugin's namespace.
     *
     * @param kind scope kind to enumerate
     * @param cursor prior page cursor, or {@code null} for the first page
     * @param limit maximum requested scopes
     * @return a page of host-authorized grants containing shared scope identities
     */
    @NonNull Page<@NonNull Grant<?>> scopes(@NonNull PluginScope kind, String cursor, int limit);

    /**
     * Returns the session grant for the active authenticated invocation.
     *
     * @return the session grant installed for the current authenticated invocation
     */
    @NonNull Grant<Scope.@NonNull SessionScope> currentSession();

    /**
     * Returns the user grant for the active authenticated invocation.
     *
     * @return the user grant installed for the current authenticated invocation
     */
    @NonNull Grant<Scope.@NonNull UserScope> currentUser();
}
