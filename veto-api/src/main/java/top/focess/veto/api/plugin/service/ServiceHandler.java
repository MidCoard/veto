package top.focess.veto.api.plugin.service;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Shared wire boundary; callers never import the provider's implementation types. */
@FunctionalInterface
public interface ServiceHandler {
    @NonNull JsonValue invoke(@NonNull JsonValue request) throws Exception;
}
