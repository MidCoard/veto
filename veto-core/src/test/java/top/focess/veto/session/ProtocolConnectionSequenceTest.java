package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.Version;
import top.focess.veto.vault.TestUsers;

@Timeout(10)
class ProtocolConnectionSequenceTest {
    private final @NonNull CommandRegistry registry = mock(CommandRegistry.class);
    private final @NonNull AgentSessions runtime = TestAgentSessions.create();
    private final @NonNull SessionService sessions = mock(SessionService.class);
    private final @NonNull SessionCommandService commands = mock(SessionCommandService.class);

    @Test
    void signedRequestIdCorrelatesIndependentlyOfHintOrder() throws Exception {
        try (var fixture = new Fixture()) {
            when(registry.hint(any(), anyString())).thenReturn(new Frame.HintInfo("hint", null));
            when(registry.dispatch(any(), eq("prompt")))
                    .thenReturn(
                            new Frame.Done(new Scope.GlobalScope(), null, -1, "complete", null, 0));
            fixture.connection.receive(new Frame.Hint("prompt", 3));
            assertEquals(3, assertInstanceOf(Frame.HintResult.class, fixture.receive()).seq());
            fixture.connection.receive(new Frame.Request(-2, null, null, "prompt"));
            assertEquals(
                    -2, assertInstanceOf(Frame.RequestAccepted.class, fixture.receive()).seq());
            assertEquals(-2, assertInstanceOf(Frame.Done.class, fixture.receive()).seq());
            assertEquals(1, fixture.closed.getCount());
            verify(registry).dispatch(any(), eq("prompt"));
        }
    }

    @Test
    void duplicateSequenceClosesWithoutFalselySettlingOriginalOperation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            when(registry.dispatch(any(), anyString()))
                    .thenAnswer(
                            invocation -> {
                                entered.countDown();
                                release.await();
                                return new Frame.Done(
                                        new Scope.GlobalScope(), null, -1, null, null, 0);
                            });
            var request = new Frame.Request(-2, null, null, "prompt");
            fixture.connection.receive(request);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            fixture.connection.receive(request);
            var failure = assertInstanceOf(Frame.Error.class, fixture.receive());
            assertEquals(0, failure.seq());
            assertTrue(fixture.closed.await(2, TimeUnit.SECONDS));
            verify(registry, times(1)).dispatch(any(), anyString());
        } finally {
            release.countDown();
        }
    }

    @Test
    void completedSequencesRemainReservedAndBudgetExhaustionRequiresReconnect() throws Exception {
        var frames = new ArrayDeque<Frame.ServerFrame>();
        var closed = new CountDownLatch(1);
        // Identifier retention must not depend on scheduling 65,535 output workers within ten
        // seconds. Real asynchronous delivery is exercised by the other connection tests.
        try (var connection =
                new ProtocolConnection(
                        "sequence-budget",
                        runtime,
                        sessions,
                        commands,
                        registry,
                        null,
                        () -> true,
                        BooleanSupplier::getAsBoolean,
                        Runnable::run,
                        frames::add,
                        ignored -> closed.countDown(),
                        Locale.ENGLISH)) {
            connection.receive(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
            assertInstanceOf(Frame.Welcome.class, frames.remove());
            // Hello reserves 1. Controls finish immediately but their sequences never become
            // reusable.
            for (long seq = 2; seq <= 65_536; seq++) {
                connection.receive(new Frame.Cancel(seq, 1));
                var result = assertInstanceOf(Frame.CancelResult.class, frames.remove());
                assertEquals(seq, result.seq());
                assertEquals(Frame.CancelStatus.NO_ACTIVE_REQUEST, result.status());
            }
            connection.receive(new Frame.Request(65_537, null, null, "must not run"));
            var error = assertInstanceOf(Frame.Error.class, frames.remove());
            assertEquals(0, error.seq());
            assertTrue(error.content().contains("budget exhausted"));
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            verify(registry, never()).dispatch(any(), anyString());
        }
        try (var fixture = new Fixture()) {
            fixture.connection.receive(new Frame.Cancel(-3, -2));
            assertInstanceOf(Frame.CancelResult.class, fixture.receive());
            fixture.connection.receive(new Frame.Hint("reuse", -3));
            assertEquals(0, assertInstanceOf(Frame.Error.class, fixture.receive()).seq());
            assertTrue(fixture.closed.await(2, TimeUnit.SECONDS));
            verify(registry, never()).hint(any(), anyString());
        }
    }

    @Test
    void queuedOutputIsReauthorizedAfterLogoutAndRelogin() {
        var tasks = new ArrayDeque<Runnable>();
        var delivered = new ArrayList<Frame.ServerFrame>();
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        var alice = TestUsers.registry().findByUserId(TestUsers.ALICE).orElseThrow();
        try (var connection =
                new ProtocolConnection(
                        "queued-authority",
                        runtime,
                        sessions,
                        commands,
                        registry,
                        null,
                        () -> true,
                        BooleanSupplier::getAsBoolean,
                        tasks::add,
                        delivered::add,
                        ignored -> {},
                        Locale.ENGLISH)) {
            connection.receive(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
            tasks.remove().run();
            delivered.clear();
            connection.setUser(alice);
            for (boolean relogin : new boolean[] {false, true}) {
                assertTrue(connection.send(Delta.message("private queued text")));
                assertTrue(
                        connection.send(
                                new Frame.Done(
                                        new Scope.GlobalScope(),
                                        null,
                                        -1,
                                        "private result",
                                        null,
                                        2)));
                assertTrue(
                        connection.send(
                                new Frame.Error(
                                        new Scope.SessionScope(TestUsers.ALICE, "session"),
                                        null,
                                        -1,
                                        "private broadcast",
                                        0,
                                        null,
                                        null)));
                connection.revokeUser(TestUsers.ALICE);
                if (relogin) connection.setUser(alice);
                tasks.remove().run();
                assertEquals(1, delivered.size());
                var completion = assertInstanceOf(Frame.Done.class, delivered.getFirst());
                assertNull(completion.content());
                assertEquals(2, completion.seq());
                assertEquals(relogin ? TestUsers.ALICE : null, completion.scope().userId());
                delivered.clear();
                connection.setUser(alice);
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        final @NonNull LinkedBlockingQueue<Frame.@NonNull ServerFrame> frames =
                new LinkedBlockingQueue<>();
        final @NonNull CountDownLatch closed = new CountDownLatch(1);
        final @NonNull ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final @NonNull ProtocolConnection connection;

        Fixture() throws Exception {
            doCallRealMethod().when(registry).terminalOutcome(any(), any());
            connection =
                    new ProtocolConnection(
                            "sequence-test",
                            runtime,
                            sessions,
                            commands,
                            registry,
                            null,
                            () -> true,
                            BooleanSupplier::getAsBoolean,
                            workers,
                            frames::add,
                            ignored -> closed.countDown(),
                            Locale.ENGLISH);
            connection.receive(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
            assertInstanceOf(Frame.Welcome.class, receive());
        }

        Frame.@NonNull ServerFrame receive() throws Exception {
            var frame = frames.poll(2, TimeUnit.SECONDS);
            if (frame == null) throw new AssertionError("Expected server response");
            return frame;
        }

        @Override
        public void close() {
            connection.close();
            workers.close();
        }
    }
}
