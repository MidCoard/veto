package top.focess.veto.plugin.contract;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.plugin.contribution.ContributionId;

/**
 * A plugin tool: real tool objects contributed by plugins. The host maps them onto its internal
 * tool model and remains responsible for authorization on every call.
 */
public interface Tool {
    /** Declared effects; the host remains responsible for authorization. */
    enum Effect {
        COMPUTATION,
        /**
         * Crosses a host trust boundary; the host gates every call with explicit approval
         * (approval-level danger, no path/command/URL arguments).
         */
        PRIVILEGED,
        /** Trusted external code whose effects require host scrutiny on every call. */
        EXTERNAL_UNKNOWN
    }

    @NonNull String description();

    JsonValue.@NonNull ObjectValue inputSchema();

    JsonValue.@NonNull ObjectValue outputSchema();

    @NonNull Effect effect();

    @NonNull Set<@NonNull ContributionId> categories();

    /** Only invoked by a host adapter after schema validation and authorization. */
    @NonNull JsonValue invoke(
            JsonValue.@NonNull ObjectValue arguments, @NonNull Cancellation cancellation)
            throws PluginFailure;
}
