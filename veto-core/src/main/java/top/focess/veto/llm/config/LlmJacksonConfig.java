package top.focess.veto.llm.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dedicated {@link ObjectMapper} for the LLM module. Kept separate from the global Spring MVC
 * mapper so its lenient, record-friendly settings cannot leak into the web layer.
 *
 * <p>Also registers {@link JavaTimeModule} as a bean so Spring Boot's auto-configuration picks it
 * up and the web-layer ObjectMapper can serialize {@code java.time.Instant} fields on JPA entities
 * (SessionEntity.createdAt, AgentPatternEntity.createdAt, etc.).
 */
@Configuration
public class LlmJacksonConfig {
    public static final String LLM_OBJECT_MAPPER = "llmObjectMapper";

    /**
     * Auto-detected by Spring Boot and registered with the default web-layer ObjectMapper. Without
     * this, {@code java.time.Instant} fields on JPA entities cause 500 errors on serialization.
     */
    @Bean
    public @NonNull Module javaTimeModule() {
        return new JavaTimeModule();
    }

    /**
     * Creates and configures a dedicated ObjectMapper for LLM operations.
     *
     * @return the configured ObjectMapper
     */
    @Bean(LLM_OBJECT_MAPPER)
    public @NonNull ObjectMapper llmObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // Models (notably Anthropic-compatible clones) occasionally emit a string where the
                // schema asks for a string array; recover the list instead of failing the tool
                // call.
                .registerModule(new LenientStringListModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
