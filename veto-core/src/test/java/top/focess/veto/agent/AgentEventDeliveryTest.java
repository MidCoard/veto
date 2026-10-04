package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.agent.AgentRunnerTest.EPISODE_TIMEOUT;
import static top.focess.veto.agent.AgentRunnerTest.binding;
import static top.focess.veto.agent.AgentRunnerTest.serviceWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.tool.builtin.FixtureLoopTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.event.AfterModelEvent;
import top.focess.veto.api.event.BeforeInputEvent;
import top.focess.veto.api.event.BeforeModelEvent;
import top.focess.veto.api.event.BeforeToolEvent;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.EventPriority;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.integration.plugins.WorkflowPluginFixture;
import top.focess.veto.llm.core.UniformLLMCaller;

/** Exercises installed event delivery and action cancellation through the real agent loop. */
class AgentEventDeliveryTest {
    @Test
    void ordinaryListenerFailureStillRunsLaterHandlerModelAndCompletesTheRealLoop()
            throws Exception {
        List<@NonNull String> calls = new CopyOnWriteArrayList<>();
        List<@NonNull VetoRequest> requests = new CopyOnWriteArrayList<>();
        String sensitiveFailure = "private listener credential=do-not-record";
        Listener hook =
                new Listener() {
                    @EventHandler(priority = EventPriority.HIGHEST)
                    public void failInput(@NonNull BeforeInputEvent event) {
                        calls.add("failing-hook");
                        throw new IllegalStateException(sensitiveFailure);
                    }

                    @EventHandler(priority = EventPriority.LOWEST)
                    public void continueInput(@NonNull BeforeInputEvent event) {
                        calls.add("later-hook");
                        event.setText("task after contained listener failure");
                    }
                };
        UniformLLMCaller caller =
                (request, modelSessionId) -> {
                    calls.add("model");
                    requests.add(request);
                    return new VetoResponse(null, null, "completed successfully");
                };

        String session = UUID.randomUUID().toString();
        try (WorkflowPluginFixture fixture =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.LISTENERS,
                                        "failure-continuation",
                                        hook)))) {

            AgentService service =
                    serviceWith(
                            new AgentServiceTestSupport.Dependencies()
                                    .plugins(fixture.sessions)
                                    .events(fixture.events),
                            caller);

            Agent agent =
                    service.getOrCreateAgent(
                            session,
                            UUID.randomUUID().toString(),
                            binding("System"),
                            List.of(),
                            UUID.randomUUID(),
                            "owner",
                            null,
                            0,
                            ToolResultPresentationMode.BASIC);
            try {
                assertTrue(agent.submitRequest("original task").await(EPISODE_TIMEOUT).success());
                assertEquals(List.of("failing-hook", "later-hook", "model"), calls);
                assertEquals(1, requests.size());
                VetoRequest delivered = requests.getFirst();
                boolean transformedTask = false;
                for (ChatMessage message : delivered.messages()) {
                    if (message.content().contains("task after contained listener failure"))
                        transformedTask = true;
                    assertFalse(message.content().contains(sensitiveFailure));
                }
                assertTrue(transformedTask);
                String history = agent.history().toString();
                assertTrue(history.contains("completed successfully"));
                assertFalse(history.contains(sensitiveFailure));
            } finally {
                service.remove(session);
            }
        }
    }

    @Test
    void installedHooksTransformInputAndModelOutputInTheRealLoop() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        List<VetoRequest> requests = new CopyOnWriteArrayList<>();
        Listener hook =
                new Listener() {
                    /** Replaces the user input. */
                    @EventHandler
                    public void onInput(@NonNull BeforeInputEvent event) {
                        events.add("input");
                        event.setText("hook supplied task");
                    }

                    /** Observes the selected model. */
                    @EventHandler
                    public void onBeforeModel(@NonNull BeforeModelEvent event) {
                        events.add("before-model");
                    }

                    /** Replaces the model output text. */
                    @EventHandler
                    public void onAfterModel(@NonNull AfterModelEvent event) {
                        events.add("after-model");
                        event.setMessage("hook supplied answer");
                    }
                };

        String session = UUID.randomUUID().toString();
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.LISTENERS,
                                        "callbacks",
                                        hook)))) {

            var service =
                    serviceWith(
                            new AgentServiceTestSupport.Dependencies()
                                    .plugins(fixture.sessions)
                                    .events(fixture.events),
                            (request, modelSessionId) -> {
                                requests.add(request);
                                return new VetoResponse(null, null, "original answer");
                            });

            var agent =
                    service.getOrCreateAgent(
                            session,
                            UUID.randomUUID().toString(),
                            binding("System"),
                            List.of(),
                            UUID.randomUUID(),
                            "owner",
                            null,
                            0,
                            ToolResultPresentationMode.BASIC);
            try {
                assertTrue(agent.submitRequest("original task").await(EPISODE_TIMEOUT).success());
                assertEquals(List.of("input", "before-model", "after-model"), events);
                assertTrue(
                        requests.getFirst().messages().stream()
                                .anyMatch(
                                        message ->
                                                message.content().contains("hook supplied task")));
                assertFalse(agent.history().toString().contains("original task"));
                assertTrue(agent.history().toString().contains("hook supplied answer"));
            } finally {
                service.remove(session);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"1,cancel", "2,cancel", "1,uncancel", "2,uncancel", "1,prevent", "2,prevent"})
    void finalToolCancellationControlsRealExecution(int toolCount, @NonNull String mode)
            throws Exception {
        var executed = new AtomicInteger();
        var earlyCalls = new AtomicInteger();
        var lateCalls = new AtomicInteger();
        Listener callbacks =
                new Listener() {
                    @EventHandler(priority = EventPriority.HIGHEST)
                    public void before(@NonNull BeforeToolEvent event) {
                        earlyCalls.incrementAndGet();
                        if (mode.equals("prevent")) event.prevent();
                        else event.cancel();
                    }

                    @EventHandler(priority = EventPriority.LOWEST)
                    public void after(@NonNull BeforeToolEvent event) {
                        lateCalls.incrementAndGet();
                        assertTrue(event.isCancelled());
                        if (mode.equals("uncancel")) event.setCancelled(false);
                    }
                };
        var first =
                AgentToolDefinition.from(
                        "fixture_one",
                        FixtureLoopTool.class,
                        FixtureLoopTool.Args.class,
                        ToolCapability.LOOP_CONTROL);
        var second =
                AgentToolDefinition.from(
                        "fixture_two",
                        FixtureLoopTool.class,
                        FixtureLoopTool.Args.class,
                        ToolCapability.LOOP_CONTROL);
        ToolEngine engine = Mockito.mock(ToolEngine.class);
        Mockito.when(engine.getActiveTools(Mockito.any())).thenReturn(List.of(first, second));
        Mockito.when(engine.resolveDefinition("fixture_one")).thenReturn(first);
        Mockito.when(engine.resolveDefinition("fixture_two")).thenReturn(second);
        Mockito.when(engine.execute(Mockito.any(), Mockito.any()))
                .thenAnswer(
                        invocation -> {
                            ToolCall call = invocation.getArgument(0);
                            if (call == null) throw new AssertionError("Missing tool call");
                            executed.incrementAndGet();
                            return ToolResult.success(
                                    call.toolName(), call.callId(), "executed fixture");
                        });
        var requests = new CopyOnWriteArrayList<VetoRequest>();

        String session = UUID.randomUUID().toString();
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.LISTENERS,
                                        "tool-cancellation",
                                        callbacks)))) {

            var service =
                    serviceWith(
                            new AgentServiceTestSupport.Dependencies()
                                    .plugins(fixture.sessions)
                                    .events(fixture.events),
                            (request, modelSessionId) -> {
                                requests.add(request);
                                if (requests.size() == 1) {
                                    var calls = new ArrayList<ToolCall>();
                                    calls.add(new ToolCall("fixture_one", Map.of()));
                                    if (toolCount == 2)
                                        calls.add(new ToolCall("fixture_two", Map.of()));
                                    return new VetoResponse(null, calls, null);
                                }
                                return new VetoResponse(null, null, "finished");
                            },
                            50,
                            engine,
                            new HitlRegistry(null, Mockito.mock(SessionInvalidations.class)));

            var agent =
                    service.getOrCreateAgent(
                            session,
                            UUID.randomUUID().toString(),
                            binding("System"),
                            List.of(),
                            UUID.randomUUID(),
                            "owner",
                            null,
                            0,
                            ToolResultPresentationMode.BASIC);
            try {
                assertTrue(
                        agent.submitRequest("Execute the fixture tools")
                                .await(EPISODE_TIMEOUT)
                                .success());
                boolean cancelled = mode.equals("cancel");
                assertEquals(cancelled ? 0 : toolCount, executed.get());
                assertEquals(toolCount, earlyCalls.get());
                assertEquals(mode.equals("prevent") ? 0 : toolCount, lateCalls.get());
                assertEquals(2, requests.size());
                var results =
                        requests.get(1).messages().stream()
                                .filter(message -> message.role().equals("tool"))
                                .toList();
                assertEquals(toolCount, results.size());
                for (var result : results) {
                    assertEquals(!cancelled, result.toolSuccess());
                    assertEquals(cancelled, result.content().contains("REFUSED"));
                    if (!cancelled) assertTrue(result.content().contains("executed fixture"));
                }
                Mockito.verify(engine, Mockito.times(cancelled ? 0 : toolCount))
                        .execute(Mockito.any(), Mockito.any());
            } finally {
                service.remove(session);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void beforeModelCancellationStopsButPreventionAloneStillCalls(boolean cancel) throws Exception {
        var calls = new AtomicInteger();
        Listener veto =
                new Listener() {
                    @EventHandler
                    public void onBeforeModel(@NonNull BeforeModelEvent event) {
                        if (cancel) event.cancel();
                        event.prevent();
                    }
                };

        String session = UUID.randomUUID().toString();
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.LISTENERS,
                                        "model-veto",
                                        veto)))) {

            var service =
                    serviceWith(
                            new AgentServiceTestSupport.Dependencies()
                                    .plugins(fixture.sessions)
                                    .events(fixture.events),
                            (request, modelSessionId) -> {
                                calls.incrementAndGet();
                                return new VetoResponse(null, null, "unreachable");
                            });

            var agent =
                    service.getOrCreateAgent(
                            session,
                            UUID.randomUUID().toString(),
                            binding("System"),
                            List.of(),
                            UUID.randomUUID(),
                            "owner",
                            null,
                            0,
                            ToolResultPresentationMode.BASIC);
            try {
                assertEquals(
                        !cancel,
                        agent.submitRequest("Do not call the model")
                                .await(EPISODE_TIMEOUT)
                                .success());
                assertEquals(cancel ? 0 : 1, calls.get());
            } finally {
                service.remove(session);
            }
        }
    }
}
