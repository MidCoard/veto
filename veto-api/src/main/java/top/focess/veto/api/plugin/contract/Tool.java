package top.focess.veto.api.plugin.contract;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contribution.ContributionId;

/**
 * A portable, out-of-process plugin tool. It declares explicit JSON schemas and exchanges {@link
 * JsonValue}, the only form a non-Java host process can consume. The host maps it onto its internal
 * tool model and remains responsible for authorization on every call.
 *
 * <p>In-process JAR plugins instead contribute a {@code CapabilityTool} through the {@code
 * veto:native-tools} point; the host compiles that record by reflection and executes it through its
 * internal tool state exactly as it does for built-in native tools.
 */
public interface Tool {
    /** Declared effects; the host remains responsible for authorization. */
    enum Effect {
        /** Deterministic local computation with no requested host trust-boundary crossing. */
        COMPUTATION,
        /**
         * Crosses a host trust boundary; the host gates every call with explicit approval
         * (approval-level danger; resource arguments remain subject to host screening).
         */
        PRIVILEGED,
        /** Trusted external code whose effects require host scrutiny on every call. */
        EXTERNAL_UNKNOWN
    }

    /**
     * Returns the tool description presented to callers.
     *
     * @return the human-readable description exposed to the model and UI
     */
    @NonNull String description();

    /**
     * Returns the declared effect used during host authorization.
     *
     * @return the declared effect class used as authorization input
     */
    @NonNull Effect effect();

    /**
     * Returns the categories used to present and classify this tool.
     *
     * @return semantic category contribution IDs used for presentation and policy
     */
    @NonNull Set<@NonNull ContributionId> categories();

    /**
     * Returns the schema used to validate invocation arguments.
     *
     * @return the JSON schema accepted by {@link #invoke}
     */
    JsonValue.@NonNull ObjectValue inputSchema();

    /**
     * Returns the schema used to validate successful results.
     *
     * @return the JSON schema describing successful invocation results
     */
    JsonValue.@NonNull ObjectValue outputSchema();

    /**
     * Executes one call after host schema validation and authorization.
     *
     * @param arguments validated immutable arguments
     * @param cancellation cooperative cancellation signal without authority
     * @return JSON result matching {@link #outputSchema()}
     * @throws PluginFailure for a sanitized public execution failure
     */
    @NonNull JsonValue invoke(
            JsonValue.@NonNull ObjectValue arguments, @NonNull Cancellation cancellation)
            throws PluginFailure;
}
