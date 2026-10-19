package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.Version;
import top.focess.veto.terminal.client.ClientSession;

class TerminalViewTest {
    @Test
    void sessionUpdatesReachReplCallbacksWithoutEchoingPromptReplies() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var terminal =
                new DumbTerminal(
                        "view-test",
                        "dumb",
                        InputStream.nullInputStream(),
                        output,
                        StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(2, 24));
            var reader = LineReaderBuilder.builder().terminal(terminal).build();
            var promptChanges = new AtomicInteger();
            var terminations = new AtomicInteger();
            var statusRefreshes = new AtomicInteger();
            var view =
                    new TerminalView(
                            reader,
                            new MordantRenderer(reader),
                            new MordantTheme(MordantTerminal.create()),
                            promptChanges::incrementAndGet,
                            terminations::incrementAndGet,
                            statusRefreshes::incrementAndGet);
            var session = new ClientSession(view, () -> 7);

            session.onFrame(new Frame.Welcome(Frame.PROTOCOL_VERSION, 1, Version.parse("2.3.4")));
            session.submit("first prompt", null);
            var prompt = new Frame.Prompt("Password:", true, 7);
            session.onFrame(prompt);
            assertEquals(1, promptChanges.get());
            assertEquals(
                    new Frame.Input("synthetic-secret", 7),
                    session.submit("synthetic-secret", prompt));
            session.onFrame(new Frame.Done(new Scope.GlobalScope(), null, -1, null, "alice", 7));
            assertEquals(2, promptChanges.get());
            assertTrue(statusRefreshes.get() > 0);

            session.onFrame(new Frame.Terminate("Backend closed"));
            assertEquals(1, terminations.get());
            terminal.flush();
            var rendered = output.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("Connected to veto-core 2.3.4."));
            assertTrue(rendered.contains("first prompt"));
            assertTrue(rendered.contains("Backend closed"));
            assertFalse(rendered.contains("synthetic-secret"));
        }
    }
}
