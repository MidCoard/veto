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
 * Registers the default (Part-5-replaceable) beans the Part-1 loop depends on: a {@link
 * CapabilityTranslator} (the deterministic {@code veto_pulse} schema + flat-tool builder) and a
 * {@link McpEngine} (no-op scaffold). Both are {@code @ConditionalOnMissingBean} — Part 5's real
 * implementations override them when present.
 *
 * <p>These defaults are required so the Spring context starts and the live terminal path runs
 * end-to-end without waiting for Part 5; they do not implement Part-5's richer logic (native-tool
 * reflection, server registration, transport dispatch).
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
