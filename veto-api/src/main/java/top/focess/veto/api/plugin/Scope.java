package top.focess.veto.api.plugin;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * Host-attributed identity boundary for a plugin operation; the value itself grants no authority.
 */
public abstract sealed class Scope
        permits Scope.GlobalScope, Scope.UserScope, Scope.SessionScope, Scope.AgentScope {
    private Scope() {}

    /**
     * Returns the owner identity for this operation.
     *
     * @return owner identity, or {@code null} for a global operation
     */
    public String owner() {
        return null;
    }

    /**
     * Returns the session identity for this operation.
     *
     * @return session identity, or {@code null} for global and user operations
     */
    public String session() {
        return null;
    }

    /**
     * Returns the agent identity for this operation.
     *
     * @return agent identity, or {@code null} unless this is an agent operation
     */
    public String agent() {
        return null;
    }

    @Override
    public final boolean equals(Object other) {
        return other instanceof Scope scope
                && getClass() == scope.getClass()
                && Objects.equals(owner(), scope.owner())
                && Objects.equals(session(), scope.session())
                && Objects.equals(agent(), scope.agent());
    }

    @Override
    public final int hashCode() {
        return Objects.hash(getClass(), owner(), session(), agent());
    }

    /** Global operation with no owner, session, or agent identity. */
    public static final class GlobalScope extends Scope {
        /** Creates an identity with no owner, session, or agent. */
        public GlobalScope() {}
    }

    /** Operation belonging to one authenticated owner. */
    public static final class UserScope extends Scope {
        private final @NonNull String owner;

        /**
         * Creates a user identity value without granting authorization.
         *
         * @param owner owner identity attributed by the host
         * @throws NullPointerException when owner is null
         */
        public UserScope(@NonNull String owner) {
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        @Override
        public @NonNull String owner() {
            return owner;
        }
    }

    /** Operation belonging to one owner and session. */
    public static final class SessionScope extends Scope {
        private final @NonNull String owner;
        private final @NonNull String session;

        /**
         * Creates a session identity value without granting authorization.
         *
         * @param owner owner identity attributed by the host
         * @param session session identity attributed by the host
         * @throws NullPointerException when owner or session is null
         */
        public SessionScope(@NonNull String owner, @NonNull String session) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.session = Objects.requireNonNull(session, "session");
        }

        @Override
        public @NonNull String owner() {
            return owner;
        }

        @Override
        public @NonNull String session() {
            return session;
        }

        /**
         * Returns the containing user identity.
         *
         * @return a user identity with this scope's owner
         */
        public @NonNull UserScope userScope() {
            return new UserScope(owner);
        }
    }

    /** Operation belonging to one owner, session, and agent. */
    public static final class AgentScope extends Scope {
        private final @NonNull String owner;
        private final @NonNull String session;
        private final @NonNull String agent;

        /**
         * Creates an agent identity value without granting authorization.
         *
         * @param owner owner identity attributed by the host
         * @param session session identity attributed by the host
         * @param agent agent identity attributed by the host
         * @throws NullPointerException when owner, session, or agent is null
         */
        public AgentScope(@NonNull String owner, @NonNull String session, @NonNull String agent) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.session = Objects.requireNonNull(session, "session");
            this.agent = Objects.requireNonNull(agent, "agent");
        }

        @Override
        public @NonNull String owner() {
            return owner;
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
         * @return a session identity with this scope's owner and session
         */
        public @NonNull SessionScope sessionScope() {
            return new SessionScope(owner, session);
        }

        /**
         * Returns the containing user identity.
         *
         * @return a user identity with this scope's owner
         */
        public @NonNull UserScope userScope() {
            return new UserScope(owner);
        }
    }
}
