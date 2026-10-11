package top.focess.veto.api.plugin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Host-attributed identity boundary for a plugin operation; the value itself grants no authority.
 *
 * <p>Every scoped operation carries the same immutable account UUID as {@code userId}. Login names
 * are display and authentication values, never scope identity. Storage grants, workflow callbacks,
 * frontend actions and lifecycle events therefore agree on account incarnation.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Scope.GlobalScope.class, name = "global"),
    @JsonSubTypes.Type(value = Scope.UserScope.class, name = "user"),
    @JsonSubTypes.Type(value = Scope.SessionScope.class, name = "session"),
    @JsonSubTypes.Type(value = Scope.AgentScope.class, name = "agent")
})
public abstract sealed class Scope
        permits Scope.GlobalScope, Scope.UserScope, Scope.SessionScope, Scope.AgentScope {
    private Scope() {}

    /**
     * Returns the canonical account UUID for this operation.
     *
     * @return canonical account UUID, or {@code null} for a global operation
     */
    @JsonProperty("userId")
    public UUID userId() {
        return null;
    }

    /**
     * Returns the session identity for this operation.
     *
     * @return session identity, or {@code null} for global and user operations
     */
    @JsonProperty("session")
    public String session() {
        return null;
    }

    /**
     * Returns the agent identity for this operation.
     *
     * @return agent identity, or {@code null} unless this is an agent operation
     */
    @JsonProperty("agent")
    public String agent() {
        return null;
    }

    @Override
    public final boolean equals(Object other) {
        return other instanceof Scope scope
                && getClass() == scope.getClass()
                && Objects.equals(userId(), scope.userId())
                && Objects.equals(session(), scope.session())
                && Objects.equals(agent(), scope.agent());
    }

    @Override
    public final int hashCode() {
        return Objects.hash(getClass(), userId(), session(), agent());
    }

    /** Global operation with no userId, session, or agent identity. */
    public static final class GlobalScope extends Scope {
        /** Creates an identity with no userId, session, or agent. */
        public GlobalScope() {}
    }

    /** Operation belonging to one authenticated account. */
    public static final class UserScope extends Scope {
        private final @NonNull UUID userId;

        /**
         * Creates a user identity value without granting authorization.
         *
         * @param userId canonical account UUID attributed by the host
         * @throws NullPointerException when userId is null
         */
        @JsonCreator
        public UserScope(@JsonProperty(value = "userId", required = true) @NonNull UUID userId) {
            this.userId = Objects.requireNonNull(userId, "userId");
        }

        @Override
        public @NonNull UUID userId() {
            return userId;
        }
    }

    /** Operation belonging to one account and session. */
    public static final class SessionScope extends Scope {
        private final @NonNull UUID userId;
        private final @NonNull String session;

        /**
         * Creates a session identity value without granting authorization.
         *
         * @param userId canonical account UUID attributed by the host
         * @param session session identity attributed by the host
         * @throws NullPointerException when userId or session is null
         */
        @JsonCreator
        public SessionScope(
                @JsonProperty(value = "userId", required = true) @NonNull UUID userId,
                @JsonProperty(value = "session", required = true) @NonNull String session) {
            this.userId = Objects.requireNonNull(userId, "userId");
            this.session = Objects.requireNonNull(session, "session");
        }

        @Override
        public @NonNull UUID userId() {
            return userId;
        }

        @Override
        public @NonNull String session() {
            return session;
        }

        /**
         * Returns the containing user identity.
         *
         * @return a user identity with this scope's userId
         */
        public @NonNull UserScope userScope() {
            return new UserScope(userId);
        }
    }

    /** Operation belonging to one userId, session, and agent. */
    public static final class AgentScope extends Scope {
        private final @NonNull UUID userId;
        private final @NonNull String session;
        private final @NonNull String agent;

        /**
         * Creates an agent identity value without granting authorization.
         *
         * @param userId canonical account UUID attributed by the host
         * @param session session identity attributed by the host
         * @param agent agent identity attributed by the host
         * @throws NullPointerException when userId, session, or agent is null
         */
        @JsonCreator
        public AgentScope(
                @JsonProperty(value = "userId", required = true) @NonNull UUID userId,
                @JsonProperty(value = "session", required = true) @NonNull String session,
                @JsonProperty(value = "agent", required = true) @NonNull String agent) {
            this.userId = Objects.requireNonNull(userId, "userId");
            this.session = Objects.requireNonNull(session, "session");
            this.agent = Objects.requireNonNull(agent, "agent");
        }

        @Override
        public @NonNull UUID userId() {
            return userId;
        }

        @Override
        public @NonNull String session() {
            return session;
        }

        @Override
        public @NonNull String agent() {
            return agent;
        }

        /**
         * Returns the containing session identity.
         *
         * @return a session identity with this scope's userId and session
         */
        public @NonNull SessionScope sessionScope() {
            return new SessionScope(userId, session);
        }

        /**
         * Returns the containing user identity.
         *
         * @return a user identity with this scope's userId
         */
        public @NonNull UserScope userScope() {
            return new UserScope(userId);
        }
    }
}
