package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.Status;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.contract.Frame;
import top.focess.veto.terminal.client.ClientSession;
import top.focess.veto.terminal.client.ClientView;
import top.focess.veto.terminal.client.StyledText;

class TerminalStatusTest {
    @Test
    void hidesBorrowedStatusAndLeavesFinalClosureToItsTerminal() throws Exception {
        var output = new ByteArrayOutputStream();
        var terminal = new StatusTerminal(output);
        try (terminal) {
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
                            },
                            () -> 1);
            var bar =
                    new TerminalStatus(
                            terminal, session, new MordantTheme(MordantTerminal.create()));
            var borrowed = Objects.requireNonNull(terminal.getStatus());
            bar.refresh();
            assertEquals(1, borrowed.size());
            output.reset();

            bar.close();

            assertEquals(0, borrowed.size());
            assertEquals(0, terminal.statusCloses);
            var rendered = output.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("\u001b[1;24r"), "Restore the full scroll region");
            assertTrue(rendered.contains("\u001b[K"), "Erase the old status line");
        }
        assertEquals(1, terminal.statusCloses);
    }

    private static final class StatusTerminal extends DumbTerminal {
        private int statusCloses;

        StatusTerminal(@NonNull OutputStream output) throws IOException {
            super(
                    "status-test",
                    "xterm",
                    InputStream.nullInputStream(),
                    output,
                    StandardCharsets.UTF_8);
            setSize(new Size(80, 24));
            status =
                    new Status(this) {
                        @Override
                        public void close() {
                            statusCloses++;
                            super.close();
                        }
                    };
        }
    }
}
