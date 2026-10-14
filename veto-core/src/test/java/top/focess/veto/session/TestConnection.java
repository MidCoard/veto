package top.focess.veto.session;

import static org.mockito.Mockito.mock;

import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;

/** Immediate attachment-aware transport around the real application connection. */
public final class TestConnection {
    private final @NonNull ProtocolConnection connection;

    public TestConnection(
            @NonNull UUID user, @NonNull Consumer<Frame.@NonNull ScopedFrame> receive) {
        connection =
                new ProtocolConnection(
                        "fixture",
                        TestAgentSessions.create(),
                        mock(SessionService.class),
                        mock(SessionCommandService.class),
                        null,
                        user,
                        () -> true,
                        BooleanSupplier::getAsBoolean,
                        (frame, version, attached) -> {
                            if (!attached.getAsBoolean()) return false;
                            if (!(frame instanceof Frame.ScopedFrame scoped))
                                throw new AssertionError("Expected session output");
                            receive.accept(scoped);
                            return true;
                        },
                        ignored -> {},
                        Locale.ENGLISH);
    }

    public @NonNull ProtocolConnection connection() {
        return connection;
    }
}
