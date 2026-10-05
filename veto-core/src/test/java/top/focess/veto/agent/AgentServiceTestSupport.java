package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.mockito.Mockito;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.DeployerPolicyConfiguration;
import top.focess.veto.agent.screening.ProtectedSetResolver;
import top.focess.veto.agent.screening.SlmScreeningProvider;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.event.EventManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.memory.TurnRecordRepository;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.observability.ObservabilityConfiguration;
import top.focess.veto.vault.CredentialVaultConfiguration;
import top.focess.veto.vault.KeysteadVault;

/** Explicit collaborators for unit fixtures using the production constructor. */
public final class AgentServiceTestSupport {
    private AgentServiceTestSupport() {}

    public static final class Dependencies {
        private @NonNull SessionPlugins plugins = Mockito.mock(SessionPlugins.class);
        private @NonNull EventManager events = Mockito.mock(EventManager.class);
        private @NonNull RequestContinuationStore continuations = memoryContinuations();
        private @NonNull KeysteadVault vault = Mockito.mock(KeysteadVault.class);
        private @NonNull ModelTierRegistry tiers = Mockito.mock(ModelTierRegistry.class);
        private Workspace workspace;
        private SessionAgentRegistry registry;
        private TurnLogService history;

        public Dependencies() {
            Mockito.when(plugins.tools(Mockito.anyString(), Mockito.any()))
                    .thenAnswer(call -> call.getArgument(1));
            Mockito.when(vault.isUnlocked(Mockito.any())).thenReturn(true);
        }

        public @NonNull Dependencies plugins(@NonNull SessionPlugins value) {
            plugins = value;
            return this;
        }

        public @NonNull Dependencies events(@NonNull EventManager value) {
            events = value;
            return this;
        }

        public @NonNull Dependencies continuations(@NonNull RequestContinuationStore value) {
            continuations = value;
            return this;
        }

        public @NonNull Dependencies vault(@NonNull KeysteadVault value) {
            vault = value;
            return this;
        }

        public @NonNull Dependencies tiers(@NonNull ModelTierRegistry value) {
            tiers = value;
            return this;
        }

        public @NonNull Dependencies workspace(@NonNull Workspace value) {
            workspace = value;
            return this;
        }

        public @NonNull Dependencies registry(@NonNull SessionAgentRegistry value) {
            registry = value;
            return this;
        }

        public @NonNull Dependencies history(@NonNull TurnLogService value) {
            history = value;
            return this;
        }
    }

    private static @NonNull RequestContinuationStore memoryContinuations() {
        var store = Mockito.mock(RequestContinuationStore.class);
        Map<String, RequestContinuationStore.Checkpoint> rows = new ConcurrentHashMap<>();
        Mockito.when(store.load(Mockito.any(), Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(
                        call ->
                                Optional.ofNullable(
                                        rows.get(
                                                call.getArgument(0)
                                                        + ":"
                                                        + call.getArgument(1)
                                                        + ":"
                                                        + call.getArgument(2))));
        Mockito.doAnswer(
                        call -> {
                            rows.put(
                                    call.getArgument(0)
                                            + ":"
                                            + call.getArgument(1)
                                            + ":"
                                            + call.getArgument(2),
                                    new RequestContinuationStore.Checkpoint(
                                            call.getArgument(3),
                                            call.getArgument(4),
                                            call.getArgument(5)));
                            return null;
                        })
                .when(store)
                .save(
                        Mockito.any(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyLong(),
                        Mockito.any());
        return store;
    }

    public static @NonNull AgentService create(
            @NonNull Dependencies dependencies,
            @NonNull ToolEngine engine,
            @NonNull HitlRegistry hitl,
            @NonNull IngressDefense ingress,
            @NonNull PromptCompiler compiler,
            @NonNull UniformLLMCaller caller,
            @NonNull ObjectMapper mapper,
            List<LoopInterceptor> interceptors,
            @NonNull String pathMode,
            long maxCalls,
            @NonNull String policyRaw,
            @NonNull String screeningMode,
            DeltaBroker broker,
            TurnLogService history) {
        var policy = new DeployerPolicyConfiguration();
        policy.setDeployerPolicy(DeployerPolicy.parse(policyRaw));
        var selectedWorkspace = dependencies.workspace;
        var selectedRegistry = dependencies.registry;
        var selectedHistory = dependencies.history;
        if (selectedHistory == null) selectedHistory = history;
        if (selectedHistory == null) selectedHistory = Mockito.mock(TurnLogService.class);
        return new AgentService(
                engine,
                hitl,
                ingress,
                compiler,
                caller,
                mapper,
                interceptors,
                pathMode,
                maxCalls,
                policy,
                screeningMode,
                broker == null ? new DeltaBroker() : broker,
                selectedHistory,
                new ProtectedSetResolver(
                        policy,
                        new ObservabilityConfiguration(),
                        new CredentialVaultConfiguration()),
                SlmScreeningProvider.unavailable(),
                selectedRegistry == null
                        ? new SessionAgentRegistry(
                                Mockito.mock(AgentInstanceRepository.class),
                                Mockito.mock(TurnRecordRepository.class),
                                Mockito.mock(SessionInvalidations.class))
                        : selectedRegistry,
                selectedWorkspace == null
                        ? Workspace.fromConfig("", "", pathMode)
                        : selectedWorkspace,
                dependencies.plugins,
                dependencies.continuations,
                dependencies.vault,
                dependencies.events,
                dependencies.tiers,
                Mockito.mock(SessionRepository.class));
    }
}
