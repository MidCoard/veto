package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;

/**
 * Common execution and effect contract for an in-process tool.
 *
 * <p>Registration makes the tool discoverable; it does not grant the implementation ambient host
 * authority. The host screens each call using {@link #getCapability()} and invokes {@link
 * #execute(Object)} only after admission. Implementations run as trusted Java and this interface is
 * not an operating-system sandbox.
 *
 * @param <T> immutable argument value decoded by the host
 */
public interface CapabilityTool<T> {
    /**
     * Identifies the authority boundary used to screen and execute the tool.
     *
     * @return the effect boundary required to authorize this tool
     */
    @NonNull ToolCapability getCapability();

    /**
     * Provides the stable name advertised to models.
     *
     * @return the stable model-visible tool name
     */
    @NonNull String getName();

    /**
     * Provides the record type accepted by this tool.
     *
     * @return the concrete argument type used for schema generation and JSON decoding
     */
    @NonNull Class<T> getArgsClass();

    /**
     * Executes one admitted call and returns its model-visible content.
     *
     * @param args decoded arguments for this call
     * @return result content; never {@code null}
     * @throws Exception when execution cannot produce a successful result
     */
    @NonNull String execute(@NonNull T args) throws Exception;
}
