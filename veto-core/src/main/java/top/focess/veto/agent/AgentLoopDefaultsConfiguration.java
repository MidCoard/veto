package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.agent.mcp.DefaultMcpEngine;
import top.focess.veto.agent.mcp.McpEngine;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.llm.config.LlmJacksonConfig;

/**
 * Registers the default (replaceable) beans the loop depends on: a {@link CapabilityTranslator}
 * (the deterministic {@code veto_pulse} schema + flat-tool builder) and a {@link McpEngine} (no-op
 * scaffold). Both are {@code @ConditionalOnMissingBean} — richer implementations override them when
 * present.
 *
 * <p>These defaults are required so the Spring context starts and the live terminal path runs
 * end-to-end; they do not implement the richer logic (native-tool reflection, server registration,
 * transport dispatch).
 */
@Configuration
public class AgentLoopDefaultsConfiguration {

    @Bean
    @ConditionalOnMissingBean(CapabilityTranslator.class)
    public CapabilityTranslator defaultCapabilityTranslator(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) ObjectMapper objectMapper) {
        return new DefaultCapabilityTranslator(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(McpEngine.class)
    public McpEngine defaultMcpEngine() {
        return new DefaultMcpEngine();
    }
}
