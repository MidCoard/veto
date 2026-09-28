package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.WorkspaceWriteCapability;

/**
 * A native tool whose filesystem operations use a call-scoped workspace-write capability.
 *
 * @param <T> immutable argument value decoded by the host
 */
public abstract class WorkspaceWriteTool<T> extends NativeTool<T> {
    /** Constructs a tool using call-scoped workspace writes. */
    protected WorkspaceWriteTool() {}

    /**
     * Executes with the supplied authorized write capability.
     *
     * @param args decoded call arguments
     * @param capability capability authorized for this invocation
     * @return model-visible result content
     * @throws Exception when execution cannot produce a successful result
     */
    public abstract @NonNull String execute(
            @NonNull T args, @NonNull WorkspaceWriteCapability capability) throws Exception;

    /**
     * Rejects execution without a host-supplied write capability.
     *
     * @param args decoded call arguments
     * @return never returns
     * @throws Exception always, because direct execution lacks authority
     */
    @Override
    public @NonNull String execute(@NonNull T args) throws Exception {
        throw new SecurityException("Host must supply an authorized workspace capability");
    }
}
