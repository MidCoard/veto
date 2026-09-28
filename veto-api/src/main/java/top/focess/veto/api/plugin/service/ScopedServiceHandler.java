package top.focess.veto.api.plugin.service;

import org.jspecify.annotations.NonNull;

import top.focess.veto.api.plugin.contract.JsonValue;

/** Handles an admitted named-service call using host-validated caller and scope facts. */
@FunctionalInterface
public interface ScopedServiceHandler {
    /**
     * Returns a bounded JSON response, or throws a stable public {@link ServiceException}.
     *
     * @param context host-derived caller and scope facts
     * @param request bounded JSON request
     * @return bounded JSON response
     * @throws Exception for provider failures; use ServiceException for a public failure code
     */
    @NonNull JsonValue invoke(@NonNull ServiceCallContext context, @NonNull JsonValue request)
            throws Exception;
}
