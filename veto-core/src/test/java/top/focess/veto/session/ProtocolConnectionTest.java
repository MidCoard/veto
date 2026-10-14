package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.ProtectedInputException;
import top.focess.veto.agent.RequestHandle;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.Version;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.vault.TestUsers;

@Timeout(10)
class ProtocolConnectionTest {
    private final @NonNull SessionService sessions = mock(SessionService.class);
    private final @NonNull SessionCommandService commands = mock(SessionCommandService.class);
    private final @NonNull AgentService agents = mock(AgentService.class);
    private final @NonNull SessionRepository repository = mock(SessionRepository.class);
    private final @NonNull AgentSessions runtime = TestAgentSessions.create(repository, agents);
    private final @NonNull VetoAgent primary = mock(VetoAgent.class);
    private final @NonNull VetoAgent child = mock(VetoAgent.class);
    private final @NonNull BlockingQueue<Frame.@NonNull ServerFrame> replies =
            new LinkedBlockingQueue<>();
    private final @NonNull AtomicBoolean authenticated = new AtomicBoolean(true);
    private final @NonNull ModelBinding model =
            new ModelBinding(
                    ProviderType.ANTHROPIC,
                    "fixture-model",
                    "credential",
                    0.2,
                    7000,
                    "https://example.test/api",
                    96000);
    private final @NonNull LlmConfig config =
            new LlmConfig(
                    model.provider(),
                    model.model(),
                    model.credentialKey(),
                    model.baseUrl(),
                    model.llmOptions());
    private final @NonNull SessionEntity entity = new SessionEntity(TestUsers.ALICE, "work");
    private final @NonNull UUID id = UUID.fromString(entity.getId());
    private final @NonNull ProtocolConnection connection =
            new ProtocolConnection(
                    "browser",
                    runtime,
                    sessions,
                    commands,
                    null,
                    TestUsers.ALICE,
                    authenticated::get,
                    operation -> authenticated.get() && operation.getAsBoolean(),
                    (frame, version, attached) -> attached.getAsBoolean() && replies.offer(frame),
                    ignored -> {},
                    Locale.CHINESE);

    @BeforeEach
    void setUp() {
        prepare(entity);
        when(primary.id()).thenReturn("primary");
        when(child.id()).thenReturn("child");
        when(child.userInteractionEnabled()).thenReturn(true);
        when(agents.agent(anyString())).thenReturn(primary);
        when(agents.sessionAgent(entity.getId(), "child")).thenReturn(child);
        connection.receive(new Frame.Hello(5, 1, Version.UNKNOWN, ""));
        assertInstanceOf(Frame.Welcome.class, replies.poll());
    }

    private void prepare(@NonNull SessionEntity session) {
        when(repository.findById(session.getId())).thenReturn(Optional.of(session));
        when(sessions.prepare(session.getId(), TestUsers.ALICE))
                .thenReturn(
                        Optional.of(
                                new SessionService.SessionConfig(
                                        session.getId(),
                                        config,
                                        ToolResultPresentationMode.BASIC)));
    }

    @AfterEach
    void close() {
        connection.close();
    }

    @Test
    void exactTextAndTierOptionsReachRunnerAndCompletionWaitsForSettlement() throws Exception {
        var request = request("episode");
        String raw = "  Explain TCP\nwithout trimming  ";
        when(primary.submitRequest(eq(raw), any(), eq(Locale.CHINESE), isNull(), any()))
                .thenAnswer(invocation -> admit(invocation, request));
        connection.receive(new Frame.Request(2, id, null, raw));
        var accepted = assertInstanceOf(Frame.RequestAccepted.class, receive());
        assertEquals(2, accepted.seq());
        assertEquals(
                new Scope.AgentScope(TestUsers.ALICE, entity.getId(), "primary"), accepted.scope());
        assertEquals("episode", accepted.requestId());
        var binding = ArgumentCaptor.forClass(LlmBinding.class);
        verify(primary)
                .submitRequest(eq(raw), binding.capture(), eq(Locale.CHINESE), isNull(), any());
        assertEquals(model.llmOptions(), binding.getValue().options());
        assertEquals(model.baseUrl(), binding.getValue().baseUrl());
        request.result().complete(AgentResult.success("done", Map.of("turns", 3)));
        assertNull(replies.poll(100, TimeUnit.MILLISECONDS));
        request.settled().complete(true);
        var done = assertInstanceOf(Frame.Done.class, receive());
        assertEquals(2, done.seq());
        assertEquals(3, done.turnNumber());
    }

    @Test
    void alreadyCompletedHandleStillAcknowledgesBeforeFinalReply() throws Exception {
        var request = request("fast");
        request.result().complete(AgentResult.success("done", Map.of()));
        request.settled().complete(true);
        when(primary.submitRequest(anyString(), any(), any(), isNull(), any()))
                .thenAnswer(invocation -> admit(invocation, request));
        connection.receive(new Frame.Request(2, id, null, "fast"));
        assertInstanceOf(Frame.RequestAccepted.class, receive());
        assertEquals(2, assertInstanceOf(Frame.Done.class, receive()).seq());
    }

    @Test
    void differentSessionsAndReusedEpisodeIdsKeepIndependentOperationSequences() throws Exception {
        var second = new SessionEntity(TestUsers.ALICE, "second");
        prepare(second);
        var firstRequest = request("shared-episode");
        var secondRequest = request("shared-episode");
        when(primary.submitRequest(eq("one"), any(), any(), isNull(), any()))
                .thenAnswer(invocation -> admit(invocation, firstRequest));
        when(primary.submitRequest(eq("two"), any(), any(), isNull(), any()))
                .thenAnswer(invocation -> admit(invocation, secondRequest));
        connection.receive(new Frame.Request(2, id, null, "one"));
        assertEquals(2, assertInstanceOf(Frame.RequestAccepted.class, receive()).seq());
        connection.receive(new Frame.Request(3, UUID.fromString(second.getId()), null, "two"));
        assertEquals(3, assertInstanceOf(Frame.RequestAccepted.class, receive()).seq());
        secondRequest.result().complete(AgentResult.success("done", Map.of()));
        secondRequest.settled().complete(true);
        assertEquals(3, assertInstanceOf(Frame.Done.class, receive()).seq());
        assertFalse(firstRequest.result().isDone());
    }

    @Test
    void cancellationSignalsExactRunnerAndAcknowledgesBeforeInlineSettlement() throws Exception {
        var request = request("cancel");
        when(primary.submitRequest(anyString(), any(), any(), isNull(), any()))
                .thenAnswer(invocation -> admit(invocation, request));
        when(request.requestCancellation())
                .thenAnswer(
                        ignored -> {
                            request.result().complete(AgentResult.success("cancelled", Map.of()));
                            request.settled().complete(true);
                            return true;
                        });
        connection.receive(new Frame.Request(2, id, null, "work"));
        assertInstanceOf(Frame.RequestAccepted.class, receive());
        connection.receive(new Frame.Cancel(3, 2));
        assertEquals(
                Frame.CancelStatus.ACCEPTED,
                assertInstanceOf(Frame.CancelResult.class, receive()).status());
        assertEquals(2, assertInstanceOf(Frame.Done.class, receive()).seq());
        verify(request).requestCancellation();
        connection.receive(new Frame.Cancel(4, 2));
        assertEquals(
                Frame.CancelStatus.NO_ACTIVE_REQUEST,
                assertInstanceOf(Frame.CancelResult.class, receive()).status());
    }

    @Test
    void cancellationDuringPreparationCannotSubmitAfterPreparationReturns() throws Exception {
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        when(sessions.prepare(entity.getId(), TestUsers.ALICE))
                .thenAnswer(
                        ignored -> {
                            entered.countDown();
                            try {
                                released.await(2, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return Optional.of(
                                    new SessionService.SessionConfig(
                                            entity.getId(),
                                            config,
                                            ToolResultPresentationMode.BASIC));
                        });
        connection.receive(new Frame.Request(2, id, null, "work"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        connection.receive(new Frame.Cancel(3, 2));
        assertEquals(
                Frame.CancelStatus.ACCEPTED,
                assertInstanceOf(Frame.CancelResult.class, receive()).status());
        released.countDown();
        assertEquals(2, assertInstanceOf(Frame.Done.class, receive()).seq());
        verify(primary, never()).submitRequest(anyString(), any(), any(), any(), any());
    }

    @Test
    void protectedInputRejectionIsCodedAndNeverAcknowledged() throws Exception {
        when(primary.submitRequest(anyString(), any(), any(), isNull(), any()))
                .thenThrow(new ProtectedInputException());
        connection.receive(new Frame.Request(2, id, null, "synthetic secret"));
        var error = assertInstanceOf(Frame.Error.class, receive());
        assertEquals(2, error.seq());
        assertEquals("PROTECTED_INPUT_UNAVAILABLE", error.code());
        assertFalse(error.content().contains("synthetic secret"));
        assertTrue(replies.isEmpty());
    }

    @Test
    void childPromptAndPrimaryCompactionShareTheSameConnection() throws Exception {
        var childRequest = request("child");
        when(child.submitUserPrompt(eq("raw"), eq(Locale.CHINESE), any()))
                .thenAnswer(invocation -> admit(invocation, childRequest));
        connection.receive(new Frame.Request(2, id, "child", "raw"));
        assertEquals(
                "child", assertInstanceOf(Frame.RequestAccepted.class, receive()).scope().agent());
        var compact = request("compact");
        when(commands.supports("/compact")).thenReturn(true);
        when(primary.compact(eq(Locale.CHINESE), any()))
                .thenAnswer(invocation -> admit(invocation, compact));
        connection.receive(new Frame.Request(3, id, "child", "/compact"));
        assertEquals(
                "primary",
                assertInstanceOf(Frame.RequestAccepted.class, receive()).scope().agent());
        verify(child, never()).submitUserPrompt(eq("/compact"), any(), any());
        verify(commands, timeout(1_000))
                .primaryNotice(TestUsers.ALICE, entity.getId(), "child", Locale.CHINESE);
    }

    @Test
    void readOnlyChildRejectsBeforeCompactionOrPromptAdmission() throws Exception {
        when(child.userInteractionEnabled()).thenReturn(false);
        when(commands.supports("/compact")).thenReturn(true);
        connection.receive(new Frame.Request(2, id, "child", "/compact"));
        assertInstanceOf(Frame.Error.class, receive());
        verify(primary, never()).compact(any(), any());
        verify(child, never()).submitUserPrompt(anyString(), any(), any());
    }

    @Test
    void bulkDeclineKeepsOwnedSessionScopeAndDoesNotCancelAnExecution() throws Exception {
        connection.receive(new Frame.DeclineVetoes(2, id));
        var done = assertInstanceOf(Frame.Done.class, receive());
        assertEquals(2, done.seq());
        assertEquals(entity.getId(), done.scope().session());
        verify(agents).declineSessionVetoes(entity.getId());
        verify(primary, never()).submitRequest(anyString(), any(), any(), any(), any());
    }

    @Test
    void invalidSessionAndMissingBrowserTargetCannotEnterTerminalCommands() throws Exception {
        connection.receive(new Frame.Request(2, UUID.randomUUID(), null, "work"));
        assertInstanceOf(Frame.Error.class, receive());
        connection.receive(new Frame.Request(3, null, null, "/login"));
        assertInstanceOf(Frame.Error.class, receive());
        verify(primary, never()).submitRequest(anyString(), any(), any(), any(), any());
    }

    @Test
    void tokenRevocationDuringPreparationPreventsSubmission() throws Exception {
        when(sessions.prepare(entity.getId(), TestUsers.ALICE))
                .thenAnswer(
                        ignored -> {
                            authenticated.set(false);
                            return Optional.of(
                                    new SessionService.SessionConfig(
                                            entity.getId(),
                                            config,
                                            ToolResultPresentationMode.BASIC));
                        });
        connection.receive(new Frame.Request(2, id, null, "work"));
        verify(sessions, timeout(1_000)).prepare(entity.getId(), TestUsers.ALICE);
        assertNull(replies.poll(100, TimeUnit.MILLISECONDS));
        verify(primary, never()).submitRequest(anyString(), any(), any(), any(), any());
    }

    @Test
    void revocationDuringInputCapturePreventsTheFinalRunnerEnqueue() throws Exception {
        var captureFinished = new CountDownLatch(1);
        var enqueued = new AtomicBoolean();
        var work = request("must-not-enqueue");
        when(primary.submitRequest(anyString(), any(), any(), isNull(), any()))
                .thenAnswer(
                        invocation -> {
                            RequestHandle.Admission admission = invocation.getArgument(4);
                            if (admission == null)
                                throw new AssertionError("Missing admission guard");
                            // Input protection begins only after the connection's early authority
                            // check.
                            authenticated.set(false);
                            try {
                                return admission.enqueue(
                                        () -> {
                                            enqueued.set(true);
                                            return work;
                                        });
                            } finally {
                                captureFinished.countDown();
                            }
                        });
        connection.receive(new Frame.Request(2, id, null, "captured input"));
        assertTrue(captureFinished.await(2, TimeUnit.SECONDS));
        assertFalse(enqueued.get(), "revocation during capture must prevent the queue mutation");
        assertNull(replies.poll(100, TimeUnit.MILLISECONDS));
        verify(primary).submitRequest(anyString(), any(), any(), isNull(), any());
    }

    @Test
    void blockedApprovalPersistenceLeavesHeartbeatAndAgentAdmissionResponsive() throws Exception {
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        when(agents.hasApprovalAgent(entity.getId(), "primary")).thenReturn(true);
        when(agents.resolveVeto("primary", "call", "ACCEPT_GENERIC", 7))
                .thenAnswer(
                        ignored -> {
                            entered.countDown();
                            assertTrue(released.await(5, TimeUnit.SECONDS));
                            return true;
                        });
        var work = request("parallel");
        when(primary.submitRequest(anyString(), any(), any(), isNull(), any()))
                .thenAnswer(invocation -> admit(invocation, work));
        var scope = new Scope.AgentScope(TestUsers.ALICE, entity.getId(), "primary");
        assertEquals(
                entity.getId(),
                runtime.requireOwned(entity.getId(), TestUsers.ALICE).scope().session());
        connection.receive(new Frame.VetoResult(scope, "call", "ACCEPT_GENERIC", 7));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var io =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    connection.receive(new Frame.Heartbeat());
                                    connection.receive(new Frame.Request(2, id, null, "parallel"));
                                });
        try {
            assertInstanceOf(Frame.HeartbeatAck.class, replies.poll(1, TimeUnit.SECONDS));
            assertEquals(
                    2,
                    assertInstanceOf(Frame.RequestAccepted.class, replies.poll(1, TimeUnit.SECONDS))
                            .seq());
            io.join(1_000);
            assertFalse(io.isAlive());
            connection.receive(new Frame.Cancel(3, 999));
            assertEquals(
                    Frame.CancelStatus.NO_ACTIVE_REQUEST,
                    assertInstanceOf(Frame.CancelResult.class, receive()).status());
        } finally {
            released.countDown();
            io.join(2_000);
        }
    }

    @Test
    void anotherAccountCannotCancelThePreviousAccountsAdmittedAgent() throws Exception {
        var work = request("old-authority");
        when(primary.submitRequest(anyString(), any(), any(), isNull(), any()))
                .thenAnswer(invocation -> admit(invocation, work));
        connection.receive(new Frame.Request(2, id, null, "private work"));
        assertInstanceOf(Frame.RequestAccepted.class, receive());
        connection.revokeUser(TestUsers.ALICE);
        connection.setUser(TestUsers.registry().findByUserId(TestUsers.BOB).orElseThrow());
        connection.receive(new Frame.Cancel(3, 2));
        assertEquals(
                Frame.CancelStatus.REJECTED,
                assertInstanceOf(Frame.CancelResult.class, receive()).status());
        verify(work, never()).requestCancellation();
        // A late account-level revocation must recheck the now-current identity.
        connection.revokeUser(TestUsers.ALICE);
        assertEquals(TestUsers.BOB, connection.userId());
    }

    @Test
    void revocationWaitsForAnAlreadyAdmittedApprovalWithoutHoldingTheConnectionMonitor()
            throws Exception {
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        when(agents.hasApprovalAgent(entity.getId(), "primary")).thenReturn(true);
        when(agents.resolveVeto("primary", "call", "ACCEPT_GENERIC", 7))
                .thenAnswer(
                        ignored -> {
                            entered.countDown();
                            assertTrue(released.await(5, TimeUnit.SECONDS));
                            return true;
                        });
        assertEquals(
                entity.getId(),
                runtime.requireOwned(entity.getId(), TestUsers.ALICE).scope().session());
        connection.receive(
                new Frame.VetoResult(
                        new Scope.AgentScope(TestUsers.ALICE, entity.getId(), "primary"),
                        "call",
                        "ACCEPT_GENERIC",
                        7));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var revoker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    connection.revokeUser(TestUsers.ALICE);
                                    revoked.countDown();
                                });
        try {
            assertFalse(revoked.await(100, TimeUnit.MILLISECONDS));
            connection.receive(new Frame.Heartbeat());
            assertInstanceOf(Frame.HeartbeatAck.class, replies.poll(1, TimeUnit.SECONDS));
            released.countDown();
            assertTrue(revoked.await(2, TimeUnit.SECONDS));
            assertNull(connection.userId());
        } finally {
            released.countDown();
            revoker.join(2_000);
        }
    }

    private static @NonNull RequestHandle admit(
            @NonNull InvocationOnMock invocation, @NonNull RequestHandle handle) {
        RequestHandle.Admission admission =
                invocation.getArgument(invocation.getArguments().length - 1);
        if (admission == null) throw new AssertionError("Missing admission guard");
        return admission.enqueue(() -> handle);
    }

    private static @NonNull RequestHandle request(@NonNull String episode) {
        var handle = mock(RequestHandle.class);
        when(handle.requestId()).thenReturn(episode);
        when(handle.result()).thenReturn(new CompletableFuture<>());
        when(handle.settled()).thenReturn(new CompletableFuture<>());
        return handle;
    }

    private Frame.@NonNull ServerFrame receive() throws InterruptedException {
        var reply = replies.poll(3, TimeUnit.SECONDS);
        if (reply == null) throw new AssertionError("Expected protocol reply");
        return reply;
    }
}
