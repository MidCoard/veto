package top.focess.veto.agent.capability;

import java.util.Objects;
import java.util.function.LongFunction;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.web.FetchedPage;
import top.focess.veto.agent.web.WebFetchExecutor;

/** Invocation-local authority to fetch one approved document. No arbitrary URL operation exists. */
public final class WebReadCapability implements Capability, AutoCloseable {
    private final @NonNull LongFunction<@NonNull FetchedPage> fetch;
    private final @NonNull ToolCallContext parent;
    private final @NonNull WebFetchExecutor reader;
    private volatile boolean closed;
    private String readerId;
    private FetchedPage page;

    WebReadCapability(
            @NonNull LongFunction<@NonNull FetchedPage> fetch,
            @NonNull ToolCallContext parent,
            @NonNull WebFetchExecutor reader) {
        this.fetch = fetch;
        this.parent = parent;
        this.reader = reader;
    }

    public @NonNull String read(@NonNull String objective) {
        if (closed) throw new SecurityException("Reader invocation has ended.");
        authorizeParent();
        return reader.read(objective, this);
    }

    /** Binds the approved destination to one child without transferring the parent's permit. */
    public void bindReader(@NonNull String agentId) {
        authorizeParent();
        if (readerId != null) throw new SecurityException("Reader is already bound.");
        readerId = agentId;
    }

    private void authorizeParent() {
        if (closed
                || !CapabilityAccess.require(ToolCapability.NETWORK_EGRESS, "web_fetch")
                        .equals(parent))
            throw new SecurityException("Reader invocation changed or ended.");
    }

    public @NonNull FetchedPage fetch(long deadline) {
        if (closed) throw new SecurityException("Reader invocation has ended.");
        var context = CapabilityAccess.require(ToolCapability.NETWORK_EGRESS);
        if (!context.equals(parent)
                && !(context.agentId().equals(readerId)
                        && context.userId().equals(parent.userId())
                        && Objects.equals(context.owner(), parent.owner())
                        && Objects.equals(context.sessionId(), parent.sessionId())
                        && context.executionPermit().toolName().equals("fetch_page")))
            throw new SecurityException("Reader invocation changed.");
        FetchedPage current = page;
        if (current == null) {
            current = fetch.apply(deadline);
            page = current;
        }
        return current;
    }

    @Override
    public void close() {
        closed = true;
        page = null;
    }
}
