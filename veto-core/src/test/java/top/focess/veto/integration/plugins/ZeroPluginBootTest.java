package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;

/** Runs in a JVM whose classpath physically excludes every bundled plugin. */
@SpringBootTest(
        properties = {
            "veto.slm.enabled=false",
            "veto.terminal.enabled=false",
            "veto.bus.websocket.port=0",
            "veto.bus.grpc.port=0",
            "veto.observability.encryption-enabled=false",
            "spring.datasource.url=jdbc:h2:mem:zero_plugins;DB_CLOSE_DELAY=-1"
        })
@MockitoBean(types = UniformLLMCaller.class)
class ZeroPluginBootTest {
    private final @NonNull PluginManager plugins;
    private final @NonNull AgentService agents;
    private final @NonNull SessionRepository sessions;
    private final @NonNull UniformLLMCaller model;
    private final @NonNull Environment environment;

    @Autowired
    ZeroPluginBootTest(
            @NonNull PluginManager plugins,
            @NonNull AgentService agents,
            @NonNull SessionRepository sessions,
            @NonNull UniformLLMCaller model,
            @NonNull Environment environment) {
        this.plugins = plugins;
        this.agents = agents;
        this.sessions = sessions;
        this.model = model;
        this.environment = environment;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startsAndCompletesCoreWorkflowWithoutPluginClasses(boolean oldBuiltinSession)
            throws Exception {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("top.focess.veto.builtin.BuiltinPlugin"));
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("top.focess.veto.secret.SecretProtectionPlugin"));
        for (String removed :
                List.of(
                        "top.focess.veto.agent.tool.builtin.UserQuestionRegistry",
                        "top.focess.veto.controller.UserQuestionController",
                        "top.focess.veto.api.agent.capability.UserInteractionCapability",
                        "top.focess.veto.builtin.questions.QuestionRuntime",
                        "top.focess.veto.agent.skills.SkillRegistry",
                        "top.focess.veto.agent.skills.SkillEntity",
                        "top.focess.veto.builtin.skills.SkillRuntime")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(removed));
        }
        assertFalse(environment.containsProperty("veto.plan.max-steps"));
        assertFalse(environment.containsProperty("veto.skills.project-dir"));
        assertTrue(plugins.plugins().isEmpty());
        assertTrue(plugins.catalog().entries(StandardContributionPoints.NATIVE_TOOLS).isEmpty());
        assertTrue(plugins.catalog().entries(StandardContributionPoints.FRONTEND).isEmpty());
        var session = new SessionEntity("owner", "zero-plugin-" + oldBuiltinSession);
        session.setPluginBindings(
                oldBuiltinSession
                        ? List.of(
                                new PluginBinding(
                                        "top.focess.builtin", "1.0.100", "saved-revision"))
                        : List.of());
        String primaryId = UUID.randomUUID().toString();
        session.setPrimaryAgentId(primaryId);
        sessions.saveAndFlush(session);
        when(model.call(any()))
                .thenReturn(new VetoResponse(null, null, "Core workflow completed", null));
        var agent =
                agents.getOrCreateAgent(
                        session.getId(),
                        primaryId,
                        new LlmBinding(
                                ProviderType.DEEPSEEK,
                                "test",
                                "unused",
                                LlmOptions.defaults(),
                                null),
                        oldBuiltinSession
                                ? List.of(
                                        TurnRecord.agentInit(
                                                1,
                                                "leader",
                                                "Historical group prompt",
                                                "test",
                                                "test"))
                                : List.of(),
                        UUID.randomUUID(),
                        "owner",
                        null);
        try {
            agent.submit("Reply once without tools");
            var result = agent.await(Duration.ofSeconds(10));
            assertTrue(result.success(), result.message());
            assertEquals("Core workflow completed", result.message());
            assertEquals(Role.STANDALONE, agent.persona().role());
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName("top.focess.veto.group.GroupRecoveryService"));
            var retained = sessions.findById(session.getId()).orElseThrow().getPluginBindings();
            assertEquals(oldBuiltinSession ? 1 : 0, retained == null ? 0 : retained.size());
        } finally {
            agents.remove(session.getId());
        }
    }
}
