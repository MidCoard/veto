package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.control.ControlHost;

/** A tool receives a host-created control port only for its admitted model call. */
public interface ControlTool<T> extends AgentTool<T> {
    @NonNull ControlHost controlHost();

    default @NonNull ToolCapability getCapability() {
        return ToolCapability.LOOP_CONTROL;
    }

    @NonNull String execute(@NonNull T arguments, @NonNull ControlHost control) throws Exception;

    default @NonNull String execute(@NonNull T arguments) throws Exception {
        return execute(arguments, controlHost());
    }
}
