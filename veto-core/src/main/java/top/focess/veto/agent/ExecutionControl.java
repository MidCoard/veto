package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.AgentState;

/** The sole scheduling fact. Public state and wait reasons are projections. */
sealed interface ExecutionControl {
    enum Activity {
        MODEL,
        TOOL
    }

    enum Wait {
        APPROVAL,
        QUESTION,
        BREAKER,
        PLUGIN,
        INTERRUPTED
    }

    enum CloseReason {
        SHUTDOWN,
        AGENT_DELETED
    }

    record Idle() implements ExecutionControl {}

    record Executing(RequestHandle request, @NonNull Activity activity)
            implements ExecutionControl {}

    record Suspended(RequestHandle request, @NonNull Activity activity, @NonNull Wait reason)
            implements ExecutionControl {}

    record Closed(@NonNull CloseReason reason) implements ExecutionControl {}

    default RequestHandle request() {
        return switch (this) {
            case Executing value -> value.request();
            case Suspended value -> value.request();
            default -> null;
        };
    }

    default boolean open() {
        return !(this instanceof Closed);
    }

    default boolean waiting(@NonNull Wait wait) {
        return this instanceof Suspended value && value.reason() == wait;
    }

    default @NonNull AgentState state() {
        return switch (this) {
            case Closed ignored -> AgentState.TERMINATED;
            case Idle ignored -> AgentState.IDLE;
            case Executing value ->
                    value.activity() == Activity.MODEL ? AgentState.RUNNING : AgentState.WAITING;
            case Suspended value ->
                    value.reason() == Wait.APPROVAL ? AgentState.INTERCEPTED : AgentState.WAITING;
        };
    }

    default @NonNull ExecutionControl withRequest(RequestHandle request) {
        return switch (this) {
            case Closed value -> value;
            case Suspended value -> new Suspended(request, value.activity(), value.reason());
            case Executing value ->
                    request == null ? new Idle() : new Executing(request, value.activity());
            case Idle ignored -> request == null ? this : new Executing(request, Activity.MODEL);
        };
    }
}
