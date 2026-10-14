package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.Version;

@Timeout(10)
class ProtocolConnectionSequenceTest {
    @Test
    void requestReservedBeforeHintCanArriveAfterTheHint() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.registry.hint(any(), anyString()))
                    .thenReturn(new Frame.HintInfo("hint", null));
            when(fixture.registry.dispatch(any(), eq("prompt")))
                    .thenReturn(
                            new Frame.Done(new Scope.GlobalScope(), null, -1, "complete", null, 0));
            fixture.connection.receive(new Frame.Hint("prompt", 3));
            assertEquals(3, assertInstanceOf(Frame.HintResult.class, fixture.receive()).seq());
            fixture.connection.receive(new Frame.Request(2, null, null, "prompt"));
            assertEquals(2, assertInstanceOf(Frame.RequestAccepted.class, fixture.receive()).seq());
            assertEquals(2, assertInstanceOf(Frame.Done.class, fixture.receive()).seq());
            assertFalse(fixture.closed.get());
            verify(fixture.registry).dispatch(any(), eq("prompt"));
        }
    }

    @Test
    void duplicateSequenceClosesWithoutFalselySettlingOriginalOperation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            when(fixture.registry.dispatch(any(), anyString()))
                    .thenAnswer(
                            invocation -> {
                                entered.countDown();
                                release.await();
                                return new Frame.Done(
                                        new Scope.GlobalScope(), null, -1, null, null, 0);
                            });
            var request = new Frame.Request(2, null, null, "prompt");
            fixture.connection.receive(request);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            fixture.connection.receive(request);
            var failure = assertInstanceOf(Frame.Error.class, fixture.receive());
            assertEquals(0, failure.seq());
            assertTrue(fixture.closed.get());
            verify(fixture.registry, times(1)).dispatch(any(), anyString());
        } finally {
            release.countDown();
        }
    }

    @Test
    void completedSequencesRemainReservedAndBudgetExhaustionRequiresReconnect() throws Exception {
        try (var fixture = new Fixture()) {
            // Hello reserves 1. Controls finish immediately but their sequences never become
            // reusable.
            for (long seq = 2; seq <= 65_536; seq++) {
                fixture.connection.receive(new Frame.Cancel(seq, 1));
                var result = assertInstanceOf(Frame.CancelResult.class, fixture.receive());
                assertEquals(seq, result.seq());
                assertEquals(Frame.CancelStatus.NO_ACTIVE_REQUEST, result.status());
            }
            fixture.connection.receive(new Frame.Request(65_537, null, null, "must not run"));
            var error = assertInstanceOf(Frame.Error.class, fixture.receive());
            assertEquals(0, error.seq());
            assertTrue(error.content().contains("budget exhausted"));
            assertTrue(fixture.closed.get());
            verify(fixture.registry, never()).dispatch(any(), anyString());
        }
        try (var fixture = new Fixture()) {
            fixture.connection.receive(new Frame.Cancel(3, 2));
            assertInstanceOf(Frame.CancelResult.class, fixture.receive());
            fixture.connection.receive(new Frame.Hint("reuse", 3));
            assertEquals(0, assertInstanceOf(Frame.Error.class, fixture.receive()).seq());
            assertTrue(fixture.closed.get());
            verify(fixture.registry, never()).hint(any(), anyString());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final @NonNull CommandRegistry registry = mock(CommandRegistry.class);
        final @NonNull LinkedBlockingQueue<Frame.@NonNull ServerFrame> frames =
                new LinkedBlockingQueue<>();
        final @NonNull AtomicBoolean closed = new AtomicBoolean();
        final @NonNull ProtocolConnection connection;

        Fixture() throws Exception {
            doCallRealMethod().when(registry).terminalOutcome(any(), any());
            connection =
                    new ProtocolConnection(
                            "sequence-test",
                            TestAgentSessions.create(),
                            mock(SessionService.class),
                            mock(SessionCommandService.class),
                            registry,
                            null,
                            () -> true,
                            BooleanSupplier::getAsBoolean,
                            (frame, version, attached) -> frames.offer(frame),
                            closed::set,
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
        }
    }
}
