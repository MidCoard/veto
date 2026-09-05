package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.config.LlmJacksonConfig;

/** Registers replaceable loop infrastructure that can be supplied by an embedding application. */
@Configuration
public class AgentLoopDefaultsConfiguration {

    @Bean
    @ConditionalOnMissingBean(CapabilityTranslator.class)
    public @NonNull CapabilityTranslator defaultCapabilityTranslator(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper objectMapper) {
        return new DefaultCapabilityTranslator(objectMapper);
    }

    /**
     * The default {@link Workspace} (built from {@code veto.workspace.*} config). Kept as a bean so
     * components that need a fallback workspace can inject it directly (e.g. the {@code
     * SystemPromptDumpTest} diagnostic). Per-session workspaces are built by {@link
     * AgentService#buildWorkspace} from the session's reported cwd and threaded through the
     * per-agent {@link Gateway} into the {@link PromptCompiler}; this bean is not that path.
     * {@code @ConditionalOnMissingBean} lets a richer workspace bean override.
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
