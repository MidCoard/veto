package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.contract.Frame;
import top.focess.veto.terminal.client.ClientSession;
import top.focess.veto.terminal.client.ClientView;
import top.focess.veto.terminal.client.StyledText;

class TerminalInputTest {
    @ParameterizedTest
    @ValueSource(strings = {"  secret with padding  ", "   ", "\tsecret\t"})
    void maskedInputPreservesExactTextWithoutCommandEcho(@NonNull String secret) {
        List<String> echoed = new ArrayList<>();
        var session =
                new ClientSession(
                        new ClientView() {
                            @Override
                            public void onDelta(@NonNull String content) {}

                            @Override
                            public void onProgress(@NonNull StyledText content) {}

                            @Override
                            public void onPrompt(Frame.@NonNull ServerFrame prompt) {}

                            @Override
                            public void onError(@NonNull StyledText content) {}

                            @Override
                            public void onTerminate(@NonNull StyledText content) {}

                            @Override
                            public void onCommandDispatched(@NonNull String line) {
                                echoed.add(line);
                            }
                        },
                        new AtomicLong(1)::incrementAndGet);
        assertEquals(
                new Frame.Request(2, null, null, "/login"),
                session.submit(VetoTerminal.normalizeInput("  /login  ", null), null));
        var prompt = new Frame.Prompt("Password:", true, 2);
        session.onFrame(prompt);
        echoed.clear();

        String prepared = VetoTerminal.normalizeInput(secret, prompt);
        assertFalse(prepared.isEmpty(), "Whitespace-only nonempty passwords must be submitted");
        assertEquals(new Frame.Input(secret, 2), session.submit(prepared, prompt));
        assertTrue(echoed.isEmpty());
        assertTrue(session.pendingQueue().isEmpty());
    }
}
