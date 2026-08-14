package top.focess.veto.llm.core;

import static top.focess.veto.util.LogValues.safe;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.llm.egress.EgressEndpoint;
import top.focess.veto.llm.egress.LlmEgress;
import top.focess.veto.llm.exceptions.LlmException;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.exceptions.PlainTextResponseException;
import top.focess.veto.llm.provider.LLMProviderStrategy;

/**
 * Default orchestrator. Owns the responsibilities that used to be scattered across providers:
 *
 * <ol>
 *   <li>select the supporting {@link LLMProviderStrategy},
 *   <li>resolve the transport target via the {@link LlmEgress} strategy (in-place key vs broker),
 *   <li>execute with bounded retry/backoff for transient ({@code retryable}) failures.
 * </ol>
 */
@Service
public class DefaultUniformLLMCaller implements UniformLLMCaller {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.llm.core.DefaultUniformLLMCaller");
    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MILLIS = 250L;
    private final @NonNull List<LLMProviderStrategy> strategies;
    private final @NonNull LlmEgress egress;

    /**
     * Constructs a new DefaultUniformLLMCaller with the specified strategies and egress.
     *
     * @param strategies the list of available LLM provider strategies
     * @param egress the egress strategy for outgoing calls
     */
    public DefaultUniformLLMCaller(
            @NonNull List<LLMProviderStrategy> strategies, @NonNull LlmEgress egress) {
        this.strategies = strategies;
        this.egress = egress;
    }

    @Override
    public @NonNull VetoResponse call(@NonNull VetoRequest request) {
        LLMProviderStrategy provider =
                strategies.stream()
                        .filter(s -> s.supports(request.providerType()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ModelCapabilityException(
                                                "No provider registered for type: "
                                                        + request.providerType()));
        EgressEndpoint endpoint =
                egress.resolve(
                        request.providerType(),
                        request.baseUrl() != null ? request.baseUrl() : provider.defaultBaseUrl(),
                        request.credentialKey());
        ResolvedRequest resolved =
                new ResolvedRequest(request, endpoint.baseUrl(), endpoint.apiKey());
        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return provider.execute(resolved);
            } catch (LlmException e) {
                last = e;
                if (!e.isRetryable() || attempt == MAX_ATTEMPTS) {
                    // Graceful degradation: a persistently non-JSON answer becomes the agent's
                    // plain-text message (a stopping turn) rather than an episode failure.
                    if (e instanceof PlainTextResponseException plainText) {
                        log.warn(
                                "Plain-text LLM response after {} attempts - surfacing as message",
                                attempt);
                        return new VetoResponse(
                                null, // thought
                                null, // calls
                                plainText.text(), // message
                                new VetoResponse.Features(false), // autonomous, stopping
                                null); // actions
                    }
                    throw e;
                }
                backoff(attempt, e);
            }
        }
        throw top.focess.veto.util.Nullness.requireNonNull(last);
    }

    private void backoff(int attempt, @NonNull LlmException cause) {
        long delay = BASE_BACKOFF_MILLIS * (1L << (attempt - 1));
        log.warn(
                "Retryable LLM failure (attempt {}/{}): {} - backing off {}ms",
                attempt,
                MAX_ATTEMPTS,
                safe(cause.getMessage()),
                delay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }
}
