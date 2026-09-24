package top.focess.veto.agent.tool;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.capability.ImportedCredentialLeases;
import top.focess.veto.api.agent.control.ControlHost;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.agent.workflow.PluginWork;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.integration.plugins.IsolatedExecutions;

/**
 * Thread-local execution context installed by {@link AgentRunner} and consumed by restricted
 * capability implementations. Concrete tools receive only their capability interface; they do not
 * choose caller identities or access runtime services through this holder. The runner clears this
 * state when the tool call ends.
 */
public final class ToolCallContextHolder {

    private static final @NonNull ThreadLocal<@Nullable ThreadState> STATE = new ThreadLocal<>();

    /** Validated control flow, separate from the model's text/tool-call result. */
    public sealed interface ResponseDirective {
        record Await(@NonNull String requestId, @NonNull PluginAwait signal)
                implements ResponseDirective {}

        record Execute(@NonNull PluginWork work) implements ResponseDirective {}

        record Finish(
                @NonNull VetoResponse response, SourceEvidence.Receipt citations, boolean publish)
                implements ResponseDirective {
            public Finish(@NonNull VetoResponse response, SourceEvidence.Receipt citations) {
                this(response, citations, true);
            }
        }
    }

    public static void installControl(@NonNull ControlHost control) {
        state().control = control;
    }

    public static @NonNull ControlHost control() {
        var control = state().control;
        if (control == null)
            throw new SecurityException(
                    "Control submission is unavailable outside an admitted model call");
        return control;
    }

    public static void transfer(@NonNull ResponseDirective result) {
        var current = state();
        if (current.response != null)
            throw new IllegalStateException("Only one control result per call");
        current.response = result;
    }

    /**
     * A feature-owned completion rule, applied only after its successful tool result is recorded.
     */
    public static void finish(@NonNull String result) {
        var current = state();
        if (current.response != null)
            throw new IllegalStateException("Only one control result per call");
        current.response =
                new ResponseDirective.Finish(new VetoResponse(null, null, result), null, false);
    }

    public static void guardWork(@NonNull UnaryOperator<PluginWork> guard) {
        var current = state();
        if (current.response instanceof ResponseDirective.Execute execution)
            current.response = new ResponseDirective.Execute(guard.apply(execution.work()));
    }

    public static void await(@NonNull PluginAwait wait) {
        var current = state();
        var context = current.context;
        String requestId = context == null ? null : context.requestId();
        if (requestId == null) throw new IllegalStateException("Await requires a live request");
        if (current.response != null)
            throw new IllegalStateException("Only one control result per call");
        current.response = new ResponseDirective.Await(requestId, wait);
    }

    public static void guardAwait(@NonNull UnaryOperator<PluginAwait> guard) {
        var current = state();
        if (current.response instanceof ResponseDirective.Await awaiting)
            current.response =
                    new ResponseDirective.Await(
                            awaiting.requestId(), guard.apply(awaiting.signal()));
    }

    public static ResponseDirective drainResponse() {
        var state = STATE.get();
        if (state == null) return null;
        var response = state.response;
        state.response = null;
        return response;
    }

    private static final @NonNull ThreadLocal<@Nullable Boolean> NO_EFFECTS = new ThreadLocal<>();

    public static void requireEffects() {
        if (Boolean.TRUE.equals(NO_EFFECTS.get()))
            throw new SecurityException(
                    "Effectful host operations are unavailable during preparation/presentation");
    }

    public static <T> T withoutEffects(@NonNull Supplier<T> operation) {
        var previous = STATE.get();
        var previousGuard = NO_EFFECTS.get();
        STATE.remove();
        NO_EFFECTS.set(true);
        try {
            return operation.get();
        } finally {
            clear();
            if (previous != null) STATE.set(previous);
            if (previousGuard == null) NO_EFFECTS.remove();
            else NO_EFFECTS.set(previousGuard);
        }
    }

    private ToolCallContextHolder() {}

    /** Sets the tool call context for the current thread. */
    public static void set(@NonNull ToolCallContext ctx) {
        state().context = ctx;
    }

    /**
     * Gets the tool call context for the current thread.
     *
     * @return the context, or {@code null} if not set (e.g. when called outside AgentRunner's
     *     execute scope)
     */
    public static ToolCallContext get() {
        ThreadState state = STATE.get();
        return state == null ? null : state.context;
    }

    static void setCurrentCallId(@NonNull String callId) {
        state().currentCallId = callId;
    }

    public static String currentCallId() {
        ThreadState state = STATE.get();
        return state == null ? null : state.currentCallId;
    }

    private static @NonNull ThreadState state() {
        ThreadState state = STATE.get();
        if (state == null) {
            state = new ThreadState();
            STATE.set(state);
        }
        return state;
    }

    /** Clears the tool call context and cancels any unconsumed wait for the current thread. */
    public static void clear() {
        var current = STATE.get();
        if (current != null && current.response instanceof ResponseDirective.Await awaiting)
            awaiting.signal().ready().cancel(false);
        try {
            IsolatedExecutions.releaseInvocation(current == null ? null : current.context);
        } finally {
            ImportedCredentialLeases.releaseInvocation(current == null ? null : current.context);
            ExecutionReceipts.discard(current == null ? null : current.context);
            STATE.remove();
        }
    }

    private static final class ThreadState {
        private ControlHost control;
        private ResponseDirective response;
        private ToolCallContext context;
        private String currentCallId;
    }
}
