package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.control.ControlHost;

/**
 * A tool receives a host-created control port only for its admitted model call.
 *
 * @param <T> immutable argument value decoded by the host
 */
public interface ControlTool<T> extends AgentTool<T> {
    /**
     * Retrieves the control port installed for this invocation.
     *
     * @return the control port bound to the current admitted call
     */
    @NonNull ControlHost controlHost();

    /**
     * @return {@link ToolCapability#LOOP_CONTROL}
     */
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.LOOP_CONTROL;
    }

    /**
     * Executes with the supplied call-scoped control port.
     *
     * @param arguments decoded call arguments
     * @param control current authorized control port
     * @return model-visible result content
     * @throws Exception when execution cannot produce a successful result
     */
    @NonNull String execute(@NonNull T arguments, @NonNull ControlHost control) throws Exception;

    /**
     * Executes using {@link #controlHost()}.
     *
     * @param arguments decoded call arguments
     * @return model-visible result content
     * @throws Exception when execution cannot produce a successful result
     */
    default @NonNull String execute(@NonNull T arguments) throws Exception {
        return execute(arguments, controlHost());
    }
}
