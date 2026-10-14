package top.focess.veto.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.Version;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;

class VetoCommandSenderInputTest {
    @Test
    void replyArrivingDuringPromptPublicationCompletesRegisteredInput() throws Exception {
        var registry = mock(CommandRegistry.class);
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        var reference = new AtomicReference<ProtocolConnection>();
        var frames = new LinkedBlockingQueue<Frame.ServerFrame>();
        when(registry.dispatch(any(), eq("prompt")))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing sender");
                            assertEquals(
                                    "immediate reply",
                                    sender.inputAsync("Prompt", false, 1_000)
                                            .get(1, TimeUnit.SECONDS));
                            return new Frame.Done(
                                    new Scope.GlobalScope(), null, -1, "complete", null, 0);
                        });
        try (var connection =
                new ProtocolConnection(
                        "terminal",
                        TestAgentSessions.create(),
                        mock(SessionService.class),
                        mock(SessionCommandService.class),
                        registry,
                        null,
                        () -> true,
                        BooleanSupplier::getAsBoolean,
                        (frame, version, attached) -> {
                            if (frame instanceof Frame.Prompt prompt) {
                                var current = reference.get();
                                if (current == null) throw new AssertionError("Missing connection");
                                // Wrong operation cannot consume the registered input.
                                current.receive(new Frame.Input("stale reply", prompt.seq() + 1));
                                current.receive(new Frame.Input("immediate reply", prompt.seq()));
                            } else frames.add(frame);
                            return true;
                        },
                        ignored -> {},
                        Locale.ENGLISH)) {
            reference.set(connection);
            connection.receive(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
            assertInstanceOf(Frame.Welcome.class, frames.poll(2, TimeUnit.SECONDS));
            connection.receive(new Frame.Request(2, null, null, "prompt"));
            var accepted =
                    assertInstanceOf(Frame.RequestAccepted.class, frames.poll(2, TimeUnit.SECONDS));
            assertEquals(2, accepted.seq());
            var done = assertInstanceOf(Frame.Done.class, frames.poll(2, TimeUnit.SECONDS));
            assertEquals(2, done.seq());
            assertEquals("complete", done.content());
        }
    }
}
