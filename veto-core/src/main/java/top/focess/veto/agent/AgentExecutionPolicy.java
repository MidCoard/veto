package top.focess.veto.agent;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.agent.IsolatedAgent;

/** Immutable terminal policy and host effect guard, independent of feature tool names. */
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public record AgentExecutionPolicy(IsolatedAgent.@Nullable Terminal terminal, Runnable check) {
    public static AgentExecutionPolicy ordinary() {
        return new AgentExecutionPolicy(null, () -> {});
    }
}
