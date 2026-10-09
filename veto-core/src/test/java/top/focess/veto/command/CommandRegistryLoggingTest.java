package top.focess.veto.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import top.focess.command.CommandPermission;
import top.focess.veto.command.commands.SignupCommand;
import top.focess.veto.vault.AuthService;

class CommandRegistryLoggingTest {
    @Test
    void rejectedLegacyPasswordAndExceptionMessageNeverReachDispatchLogs() {
        var logger = (Logger) LoggerFactory.getLogger("top.focess.veto.command.CommandRegistry");
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var sender = mock(VetoCommandSender.class);
            when(sender.hasPermission(any(CommandPermission.class))).thenReturn(true);
            when(sender.terminalId()).thenReturn("terminal");
            var registry = new CommandRegistry(null);
            registry.register(new SignupCommand(mock(AuthService.class)));
            assertNotNull(registry.dispatch(sender, "/signup alice legacy-secret"));
            when(sender.hasPermission(any(CommandPermission.class)))
                    .thenThrow(new IllegalStateException("echoed-secret"));
            assertNotNull(registry.dispatch(sender, "/signup alice echoed-secret"));

            assertFalse(events.list.isEmpty());
            String messages =
                    events.list.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .reduce("", (left, right) -> left + right);
            assertTrue(messages.contains("signup"));
            assertTrue(messages.contains("IllegalStateException"));
            assertFalse(messages.contains("legacy-secret"));
            assertFalse(messages.contains("echoed-secret"));
            assertFalse(messages.contains("alice"));
            assertTrue(events.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally {
            assertTrue(logger.detachAppender(events));
            events.stop();
        }
    }
}
