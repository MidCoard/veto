package top.focess.veto.plugin.contract;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.plugin.contribution.ContributionId;

/**
 * A plugin tool: real tool objects contributed by plugins. The host maps them onto its internal
 * tool model and remains responsible for authorization on every call.
 *
 * <p>A tool is authored in one of two shapes, mirroring how the host models its own tools:
 *
 * <ul>
 *   <li>{@link RecordTool} — an in-process Java plugin declares its arguments as a record and the
 *       host compiles the input schema by reflection, exactly as it does for built-in native tools.
 *   <li>{@link SchemaTool} — a portable or out-of-process plugin declares explicit JSON schemas and
 *       exchanges {@link JsonValue}, the only form a non-Java host process can consume.
 * </ul>
 */
public sealed interface Tool {
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

    @NonNull Effect effect();

    @NonNull Set<@NonNull ContributionId> categories();

    /**
     * Record-authored tool. The host reflects {@link #argsType()} into a JSON Schema, validates the
     * call arguments against it, deserializes them into an {@code argsType} instance, and only then
     * invokes the tool — so the handler receives typed arguments and returns a typed result the
     * host serializes back to JSON.
     */
    non-sealed interface RecordTool extends Tool {
        /** The Java record type the host deserializes validated arguments into. */
        @NonNull Class<?> argsType();

        /** Only invoked by a host adapter after argument deserialization and authorization. */
        @NonNull Object invoke(@NonNull Object args, @NonNull Cancellation cancellation)
                throws PluginFailure;
    }

    /**
     * Schema-authored tool. The host validates arguments and results against the supplied JSON
     * schemas and exchanges {@link JsonValue}; this is the portable form used by out-of-process
     * script plugins.
     */
    non-sealed interface SchemaTool extends Tool {
        JsonValue.@NonNull ObjectValue inputSchema();

        JsonValue.@NonNull ObjectValue outputSchema();

        /** Only invoked by a host adapter after schema validation and authorization. */
        @NonNull JsonValue invoke(
                JsonValue.@NonNull ObjectValue arguments, @NonNull Cancellation cancellation)
                throws PluginFailure;
    }
}
