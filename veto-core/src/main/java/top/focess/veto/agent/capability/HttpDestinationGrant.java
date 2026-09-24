package top.focess.veto.agent.capability;

import java.util.Set;
import java.util.function.LongFunction;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.tool.ExecutionReceipts;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.http.ApprovedHttpDestination;
import top.focess.veto.api.http.HttpDocument;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.integration.plugins.IsolatedExecutions;

/** One exact screened destination; cached content has the same authority as its first fetch. */
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class HttpDestinationGrant implements ApprovedHttpDestination {
    private final LongFunction<HttpDocument> fetch;
    private final ToolCallContext parent;
    private final Thread parentThread = Thread.currentThread();
    private volatile boolean closed;
    private volatile IsolatedExecutions.@Nullable Scope child;
    private volatile @Nullable String operation;
    private volatile @Nullable HttpDocument cached;

    HttpDestinationGrant(LongFunction<HttpDocument> fetch, ToolCallContext parent) {
        this.fetch = fetch;
        this.parent = parent;
    }

    private void parent() {
        if (closed
                || parentThread.isInterrupted()
                || !parent.equals(CapabilityAccess.require(ToolCapability.NETWORK_EGRESS)))
            throw new SecurityException("Approved destination invocation changed or ended");
    }

    public synchronized void bind(IsolatedAgent.Runtime runtime, String operation) {
        parent();
        if (child != null
                || !(runtime instanceof IsolatedExecutions.Scope scope)
                || !parent.equals(scope.parent()))
            throw new SecurityException(
                    "Destination cannot be rebound or transferred to this child");
        scope.bind(this);
        child = scope;
        this.operation = operation;
    }

    public void requireOperation(Set<String> tools) {
        var bound = operation;
        if (bound == null || !tools.contains(bound))
            throw new SecurityException("Destination operation is not a private tool");
    }

    private long authorizeFetch() {
        if (closed || parentThread.isInterrupted())
            throw new SecurityException("Destination grant closed");
        var context = ToolCallContextHolder.get();
        var target = child;
        var name = operation;
        if (parent.equals(context)) {
            parent();
            if (target != null) target.check();
            return target == null ? Long.MAX_VALUE : target.deadline();
        }
        if (target == null || name == null)
            throw new SecurityException("No child destination grant");
        target.authorize(name);
        return target.deadline();
    }

    public HttpDocument fetch() {
        long deadline = authorizeFetch();
        var value = cached;
        if (value != null) return value;
        value = fetch.apply(deadline);
        authorizeFetch();
        synchronized (this) {
            if (closed) throw new SecurityException("Destination grant closed during fetch");
            var existing = cached;
            if (existing != null) return existing;
            cached = value;
            return value;
        }
    }

    public void publish(IsolatedAgent handle) {
        parent();
        if (!(handle instanceof IsolatedExecutions.Child execution)
                || execution.scope() != child
                || !execution.settledSuccessfully())
            throw new SecurityException(
                    "Execution is not a successful settled child of this grant");
        var scope = execution.scope();
        scope.authorizeParent();
        ExecutionReceipts.publish(parent, scope.id(), scope::provenanceLive);
    }

    public synchronized void close() {
        closed = true;
        cached = null;
    }
}
