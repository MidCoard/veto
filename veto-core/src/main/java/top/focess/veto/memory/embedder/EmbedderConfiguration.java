package top.focess.veto.memory.embedder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.credential.CredentialResolver;

/**
 * Selects the active {@link Embedder} bean.
 *
 * <ul>
 *   <li>When {@code veto.memory.embedder.provider} is set, a {@link ProviderEmbedder} wins - it
 *       calls the configured provider's embeddings API.
 *   <li>Otherwise the {@link HashEmbedder} local stub is registered
 *       ({@code @ConditionalOnMissingBean} so it yields to any other Embedder, including a future
 *       local-ONNX embedder).
 * </ul>
 *
 * <p>This mirrors the {@code @ConditionalOnMissingBean} default pattern used by {@link
 * top.focess.veto.agent.AgentLoopDefaultsConfiguration}: a richer bean overrides the default,
 * rather than the mutually-exclusive {@code @ConditionalOnProperty} the stores use.
 */
@Configuration
@EnableConfigurationProperties(EmbedderProperties.class)
public class EmbedderConfiguration {

    @Bean
    @ConditionalOnProperty("veto.memory.embedder.provider")
    public @NonNull Embedder providerEmbedder(
            @NonNull EmbedderProperties props,
            @NonNull CredentialResolver resolver,
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper) {
        return new ProviderEmbedder(props, resolver, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(Embedder.class)
    public @NonNull Embedder hashEmbedder() {
        return new HashEmbedder();
    }
}
