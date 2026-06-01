package top.focess.veto.llm.core;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.llm.egress.EgressEndpoint;
import top.focess.veto.llm.egress.LlmEgress;
import top.focess.veto.llm.exceptions.LlmException;
import top.focess.veto.llm.exceptions.ModelCapabilityException;
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
    private static final Logger log = LoggerFactory.getLogger(DefaultUniformLLMCaller.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MILLIS = 250L;
    private final List<LLMProviderStrategy> strategies;
    private final LlmEgress egress;

    /**
     * Constructs a new DefaultUniformLLMCaller with the specified strategies and egress.
     *
     * @param strategies the list of available LLM provider strategies
     * @param egress the egress strategy for outgoing calls
     */
    public DefaultUniformLLMCaller(List<LLMProviderStrategy> strategies, LlmEgress egress) {
        this.strategies = strategies;
        this.egress = egress;
    }

    @Override
    public VetoResponse call(VetoRequest request) {
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
                        request.providerType(), provider.defaultBaseUrl(), request.credentialKey());
        ResolvedRequest resolved =
                new ResolvedRequest(request, endpoint.baseUrl(), endpoint.apiKey());
        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return provider.execute(resolved);
            } catch (LlmException e) {
                last = e;
                if (!e.isRetryable() || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                backoff(attempt, e);
            }
        }
        throw last; // unreachable, retained for the compiler.
    }

    private void backoff(int attempt, LlmException cause) {
        long delay = BASE_BACKOFF_MILLIS * (1L << (attempt - 1));
        log.warn(
                "Retryable LLM failure (attempt {}/{}): {} - backing off {}ms",
                attempt,
                MAX_ATTEMPTS,
                cause.getMessage(),
                delay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }
}
