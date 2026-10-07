package top.focess.veto.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.Version;
import top.focess.veto.terminal.IpcServer;

class VetoCommandSenderInputTest {
    @Test
    void replyArrivingDuringPromptPublicationCompletesRegisteredInput() throws Exception {
        var server = mock(IpcServer.class);
        var sender = new VetoCommandSender(server, null, "terminal", Version.UNKNOWN, ".");
        doAnswer(
                        invocation -> {
                            assertTrue(sender.receiveInput("immediate reply"));
                            return null;
                        })
                .when(server)
                .send(eq("terminal"), any(IpcFrame.Prompt.class));

        var future = sender.inputAsync("Prompt", false, 1_000);

        assertEquals("immediate reply", future.get(1, TimeUnit.SECONDS));
    }
}
