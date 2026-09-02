package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.DefaultToolEngine;
import top.focess.veto.agent.mcp.ToolEngine;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.config.LlmJacksonConfig;

/**
 * Registers the default (replaceable) beans the loop depends on: a {@link CapabilityTranslator}
 * (the deterministic {@code veto_pulse} schema + flat-tool builder) and a {@link ToolEngine} (no-op
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
    public @NonNull CapabilityTranslator defaultCapabilityTranslator(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper objectMapper) {
        return new DefaultCapabilityTranslator(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(ToolEngine.class)
    public @NonNull ToolEngine defaultToolEngine() {
        return new DefaultToolEngine();
    }

    /**
     * The default {@link Workspace} (built from {@code veto.workspace.*} config). Kept as a bean so
     * components that need a fallback workspace can inject it directly (e.g. the {@code
     * SystemPromptDumpTest} diagnostic). Per-session workspaces are built by {@link
     * top.focess.veto.agent.AgentService#buildWorkspace} from the session's reported cwd and
     * threaded through the per-agent {@link top.focess.veto.agent.intercept.Gateway} into the
     * {@link PromptCompiler}; this bean is not that path. {@code @ConditionalOnMissingBean} lets a
     * richer workspace bean override.
     */
    @Bean
    @ConditionalOnMissingBean(Workspace.class)
    public @NonNull Workspace defaultWorkspace(
            @Value("${veto.workspace.root}") @NonNull String legacyRoot,
            @Value("${veto.workspace.roots}") @NonNull String rootsCsv,
            @Value("${veto.workspace.path-mode}") @NonNull String pathMode) {
        return Workspace.fromConfig(legacyRoot, rootsCsv, pathMode);
    }
}
