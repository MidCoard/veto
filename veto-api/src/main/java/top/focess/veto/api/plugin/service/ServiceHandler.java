package top.focess.veto.api.plugin.service;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;

/**
 * Bounded JSON wire boundary for a named service. Callers never import provider implementation
 * types. The host invokes handlers on the caller thread after admission and sanitizes unexpected
 * failures; providers should use {@link ServiceException} for public protocol failures.
 */
@FunctionalInterface
public interface ServiceHandler {
    /**
     * Handles one admitted request synchronously on the caller thread.
     *
     * @param request bounded JSON request
     * @return bounded JSON response
     * @throws Exception for a protocol or provider failure; use {@link ServiceException} for a
     *     stable public failure code
     */
    @NonNull JsonValue invoke(@NonNull JsonValue request) throws Exception;
}
