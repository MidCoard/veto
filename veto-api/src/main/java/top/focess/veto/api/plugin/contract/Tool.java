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
        COMPUTATION,
        /**
         * Crosses a host trust boundary; the host gates every call with explicit approval
         * (approval-level danger; resource arguments remain subject to host screening).
         */
        PRIVILEGED,
        /** Trusted external code whose effects require host scrutiny on every call. */
        EXTERNAL_UNKNOWN
    }

    @NonNull String description();

    @NonNull Effect effect();

    @NonNull Set<@NonNull ContributionId> categories();

    JsonValue.@NonNull ObjectValue inputSchema();

    JsonValue.@NonNull ObjectValue outputSchema();

    /** Only invoked by a host adapter after schema validation and authorization. */
    @NonNull JsonValue invoke(
            JsonValue.@NonNull ObjectValue arguments, @NonNull Cancellation cancellation)
            throws PluginFailure;
}
