package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/** A plugin-owned browser module and its scoped backend actions. */
public interface FrontendContribution {
    /**
     * Returns the complete browser ESM source.
     *
     * @return the complete browser ESM source
     */
    @NonNull String module();

    /**
     * Handles a host-authorized action from this module.
     *
     * @param scope authenticated user, session, and agent
     * @param action action name requested by the module
     * @param arguments bounded JSON arguments
     * @return bounded JSON response
     * @throws PluginFailure when the action cannot be completed
     */
    @NonNull JsonValue handle(
            Scope.@NonNull AgentScope scope,
            @NonNull String action,
            JsonValue.@NonNull ObjectValue arguments)
            throws PluginFailure;
}
