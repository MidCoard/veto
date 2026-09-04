package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.Capability;
import top.focess.veto.agent.capability.CapabilityResolver;

/** A native tool whose effects are available only through one typed, call-scoped capability. */
public interface CapabilityTool<T, C extends Capability> extends NativeTool<T> {

    /** The exact capability type required by this tool. */
    @NonNull Class<C> getCapabilityClass();

    /** Executes using the capability issued for the current screened call. */
    @NonNull String execute(@NonNull T args, @NonNull C capability) throws Exception;

    @Override
    default @NonNull String execute(@NonNull T args) throws Exception {
        return execute(args, CapabilityResolver.require(getCapabilityClass()));
    }
}
