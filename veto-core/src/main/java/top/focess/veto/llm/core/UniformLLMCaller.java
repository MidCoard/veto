package top.focess.veto.llm.core;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.LlmException;

/**
 * The main entry point for the Veto agent loop to call an LLM. Implementations resolve credentials,
 * select the right provider, apply retry/backoff, and return a normalized {@link VetoResponse}.
 */
public interface UniformLLMCaller {
    /**
     * Executes a request without persisted-session context by delegating to {@link
     * #call(VetoRequest, String)} with a null session id.
     *
     * @param request the standardized LLM request
     * @return the normalized response from the LLM
     * @throws LlmException if the call fails permanently (auth, capability) or exhausts retries.
     */
    default @NonNull VetoResponse call(@NonNull VetoRequest request) {
        return call(request, null);
    }

    /**
     * Executes a request with optional persisted-session context. Implementations that resolve
     * plugin-provided transports use a supplied session id to enforce its exact plugin revision
     * pin. A null session id selects the same no-session path as {@link #call(VetoRequest)}.
     *
     * @param request the standardized LLM request
     * @param sessionId the persisted session id, or null when there is no session context
     * @return the normalized response from the LLM
     * @throws LlmException if the call fails permanently or exhausts retries
     */
    @NonNull VetoResponse call(@NonNull VetoRequest request, String sessionId);
}
