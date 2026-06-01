package top.focess.veto.llm.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dedicated {@link ObjectMapper} for the LLM module. Kept separate from the global Spring MVC
 * mapper so its lenient, record-friendly settings cannot leak into the web layer.
 */
@Configuration
public class LlmJacksonConfig {
    public static final String LLM_OBJECT_MAPPER = "llmObjectMapper";

    /**
     * Creates and configures a dedicated ObjectMapper for LLM operations.
     *
     * @return the configured ObjectMapper
     */
    @Bean(LLM_OBJECT_MAPPER)
    public ObjectMapper llmObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
