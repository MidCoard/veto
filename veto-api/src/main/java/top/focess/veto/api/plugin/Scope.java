package top.focess.veto.api.plugin;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/** Host-attributed identity boundary for a plugin operation. */
public abstract sealed class Scope
        permits Scope.GlobalScope, Scope.UserScope, Scope.SessionScope, Scope.AgentScope {
    private Scope() {}

    /** Owner identity, absent only for a global operation. */
    public String owner() {
        return null;
    }

    /** Session identity, present for session and agent operations. */
    public String session() {
        return null;
    }

    /** Agent identity, present only for agent operations. */
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
        public GlobalScope() {}
    }

    /** Operation belonging to one authenticated owner. */
    public static final class UserScope extends Scope {
        private final @NonNull String owner;

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
    }

    /** Operation belonging to one owner, session, and agent. */
    public static final class AgentScope extends Scope {
        private final @NonNull String owner;
        private final @NonNull String session;
        private final @NonNull String agent;

        public AgentScope(
                @NonNull String owner, @NonNull String session, @NonNull String agent) {
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
    }
}
