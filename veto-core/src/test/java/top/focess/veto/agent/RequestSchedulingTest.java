package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.ExecutionControl.Wait;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefenseTestSupport;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.bus.SessionActions;
import top.focess.veto.contract.Frame;
import top.focess.veto.event.EventManager;
import top.focess.veto.vault.TestUsers;

class RequestSchedulingTest {
    @Test
    void protectedInputRejectionOccursBeforeAdmission() {
        var runner = runner();
        var events = mock(EventManager.class);
        doAnswer(answerVoid((@NonNull BeforeTextCommitEvent event) -> event.setCancelled(true)))
                .when(events)
                .submit(any(BeforeTextCommitEvent.class));
        runner.attachEventManager(events);
        assertThrows(
                ProtectedInputException.class,
                () -> runner.startTask(null, new AgentAction.UserPromptAction("protected input")));
        assertFalse(runner.hasPendingWork());
        verify(events).submit(any(BeforeTextCommitEvent.class));
    }

    @Test
    void authorityGuardRunsAfterProtectionAndCanRejectWithoutEnqueueing() {
        var runner = runner();
        var events = mock(EventManager.class);
        doAnswer(
                        answerVoid(
                                (@NonNull BeforeTextCommitEvent event) ->
                                        event.setText("protected reference")))
                .when(events)
                .submit(any(BeforeTextCommitEvent.class));
        runner.attachEventManager(events);
        assertThrows(
                IllegalStateException.class,
                () ->
                        runner.startTask(
                                null,
                                new AgentAction.UserPromptAction("raw secret"),
                                Locale.GERMAN,
                                commit -> {
                                    verify(events).submit(any(BeforeTextCommitEvent.class));
                                    assertFalse(runner.hasPendingWork());
                                    throw new IllegalStateException(
                                            "Authority revoked during protection");
                                }));
        assertFalse(runner.hasPendingWork());
    }

    @Test
    void queuedPromptIsProtectedBeforeAdmissionAndIgnoresEarlierCancellation() {
        var runner = runner();
        var previousAction = new AgentAction.UserPromptAction("previous");
        var previous = runner.startTask(null, previousAction);
        assertTrue(runner.beginRequest(new QueuedRequest(previousAction, previous)));
        previous.cancelled = true;
        var events = mock(EventManager.class);
        doAnswer(
                        answerVoid(
                                (@NonNull BeforeTextCommitEvent event) ->
                                        event.setText("protected reference")))
                .when(events)
                .submit(any(BeforeTextCommitEvent.class));
        runner.attachEventManager(events);
        var request = runner.startTask(null, new AgentAction.UserPromptAction("raw secret"));
        var value = ReflectionTestUtils.getField(runner, "actionQueue");
        if (!(value instanceof BlockingQueue<?> queue))
            throw new AssertionError("Missing action queue");
        boolean found =
                queue.stream()
                        .anyMatch(
                                item ->
                                        item instanceof QueuedRequest queued
                                                && queued.handle() == request
                                                && queued.action()
                                                        instanceof
                                                        AgentAction.UserPromptAction prompt
                                                && prompt.prompt().equals("protected reference"));
        assertTrue(found);
        verify(events).submit(any(BeforeTextCommitEvent.class));
    }

    @ParameterizedTest
    @EnumSource(Wait.class)
    void completionReleasesTransientWaitsButKeepsContinuationBarriers(@NonNull Wait reason) {
        var runner = runner();
        var action = new AgentAction.UserPromptAction("task");
        var request = runner.startTask(null, action);
        assertTrue(runner.beginRequest(new QueuedRequest(action, request)));
        runner.beginWait(reason);
        runner.complete(AgentResult.failure("failed", Map.of()));
        assertTrue(request.result().isDone());
        assertFalse(request.settled().isDone(), "Result delivery is not execution settlement");
        runner.finishTurn(request, false);
        assertTrue(request.settled().isDone());
        if (reason == Wait.BREAKER || reason == Wait.INTERRUPTED) {
            assertTrue(runner.control().waiting(reason));
        } else {
            assertEquals(AgentState.IDLE, runner.state());
            assertNull(runner.executionWaitReason());
        }
    }

    @Test
    void cancelledQueuedRequestPublishesItsFinalIdleSnapshot() throws Exception {
        var changes = new LinkedBlockingQueue<Frame.Action>();
        var runner =
                runner(
                        frame -> {
                            if (frame instanceof Frame.Action action) changes.add(action);
                        });
        var request = runner.startTask(null, new AgentAction.UserPromptAction("queued"));
        assertTrue(changes.poll() != null);
        request.cancelled = true;
        var agent = new VetoAgent(runner.personaView(), runner);
        try {
            assertFalse(request.await(Duration.ofSeconds(5)).success());
            assertTrue(request.settled().get(5, TimeUnit.SECONDS));
            assertTrue(changes.poll(5, TimeUnit.SECONDS) != null);
            assertFalse(runner.hasPendingWork());
        } finally {
            agent.shutdown();
            assertTrue(agent.awaitTermination(Duration.ofSeconds(5)));
        }
    }

    @Test
    void endingAnotherWaitCannotReleaseApproval() {
        var runner = runner();
        runner.beginWait(Wait.APPROVAL);
        runner.clearWait(Wait.QUESTION);
        runner.setActivity(ExecutionControl.Activity.MODEL);
        assertEquals(AgentState.INTERCEPTED, runner.state());
        runner.clearWait(Wait.APPROVAL);
        assertEquals(AgentState.IDLE, runner.state());
    }

    @Test
    void cancellationRejectsAnotherRunnersHandle() throws InterruptedException {
        var runner = runner();
        var foreign = runner().startTask(null, new AgentAction.UserPromptAction("foreign"));
        assertFalse(runner.cancelTask(foreign, Duration.ZERO));
        assertFalse(foreign.result().isDone());
    }

    @Test
    void queuedRequestsKeepTheirOwnModelAndLocale() {
        var runner = runner();
        var firstBinding =
                new LlmBinding(
                        ProviderType.ANTHROPIC, "first", "first", LlmOptions.defaults(), null);
        var secondBinding =
                new LlmBinding(
                        ProviderType.ANTHROPIC, "second", "second", LlmOptions.defaults(), null);
        var firstAction = new AgentAction.UserPromptAction("first");
        var secondAction = new AgentAction.UserPromptAction("second");
        var first = runner.startTask(null, firstAction, firstBinding, Locale.GERMAN);
        var second = runner.startTask(null, secondAction, secondBinding, Locale.FRENCH);
        assertTrue(runner.beginRequest(new QueuedRequest(firstAction, first)));
        runner.resolveConfiguration();
        assertEquals(firstBinding, runner.binding());
        assertEquals(Locale.GERMAN, runner.locale());
        runner.complete(AgentResult.success("done", Map.of()));
        runner.finishTurn(first, false);
        assertTrue(runner.beginRequest(new QueuedRequest(secondAction, second)));
        runner.resolveConfiguration();
        assertEquals(secondBinding, runner.binding());
        assertEquals(Locale.FRENCH, runner.locale());
    }

    @Test
    void shutdownBeforeThreadStartDoesNotLeaveTheWorkerBlocked() throws InterruptedException {
        var runner = runner();
        var request = runner.startTask(null, new AgentAction.UserPromptAction("queued"));
        runner.shutdown();
        var agent = new VetoAgent(runner.personaView(), runner);
        assertTrue(agent.awaitTermination(Duration.ofSeconds(5)));
        assertTrue(request.result().isDone());
        assertTrue(request.settled().isDone());
    }

    @Test
    void closeAndSubmissionRaceCannotLoseAnAcceptedRequest() throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            var runner = runner();
            var start = new CountDownLatch(1);
            var submitted = new CompletableFuture<Optional<RequestHandle>>();
            var closed = new CompletableFuture<Boolean>();
            Thread submitter =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            start.await();
                                            submitted.complete(
                                                    Optional.of(
                                                            runner.startTask(
                                                                    null,
                                                                    new AgentAction
                                                                            .UserPromptAction(
                                                                            "racing"))));
                                        } catch (IllegalStateException rejected) {
                                            submitted.complete(Optional.empty());
                                        } catch (InterruptedException error) {
                                            submitted.completeExceptionally(error);
                                        }
                                    });
            Thread stopper =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            start.await();
                                            runner.shutdown();
                                            closed.complete(true);
                                        } catch (InterruptedException error) {
                                            closed.completeExceptionally(error);
                                        }
                                    });
            start.countDown();
            var accepted = submitted.get(5, TimeUnit.SECONDS).orElse(null);
            assertTrue(closed.get(5, TimeUnit.SECONDS));
            assertTrue(submitter.join(Duration.ofSeconds(5)));
            assertTrue(stopper.join(Duration.ofSeconds(5)));
            var agent = new VetoAgent(runner.personaView(), runner);
            assertTrue(agent.awaitTermination(Duration.ofSeconds(5)));
            assertTrue(
                    runner.control() instanceof ExecutionControl.Closed retired
                            && retired.reason() == ExecutionControl.CloseReason.SHUTDOWN,
                    "Retirement retains the accepted shutdown reason");
            if (accepted != null) {
                assertFalse(accepted.await(Duration.ofSeconds(5)).success());
                assertTrue(accepted.settled().get(5, TimeUnit.SECONDS));
            }
        }
    }

    private static @NonNull AgentRunner runner() {
        return runner(AgentEventSink.none());
    }

    private static @NonNull AgentRunner runner(@NonNull AgentEventSink sink) {
        String id = UUID.randomUUID().toString();
        var gateway = mock(Gateway.class);
        when(gateway.readHistory()).thenReturn(new ReadHistory());
        var compiler = mock(PromptCompiler.class);
        var tools = mock(ToolEngine.class);
        return new AgentRunner(
                id,
                new AgentPersona(id, "Fixture", "Fixture", Set.of()),
                tools,
                new ToolExecutionBoundary(
                        id,
                        UUID.fromString(id),
                        TestUsers.OWNER,
                        tools,
                        gateway,
                        new HitlRegistry(null, Mockito.mock(SessionActions.class)),
                        IngressDefenseTestSupport.inMemory()),
                List.of(),
                compiler,
                (request, session) -> new VetoResponse(null, null, "done"),
                new ObjectMapper(),
                50,
                new LlmBinding(ProviderType.ANTHROPIC, "test", "test", LlmOptions.defaults(), null),
                sink,
                TestUsers.OWNER,
                null,
                UUID.fromString(id));
    }
}
