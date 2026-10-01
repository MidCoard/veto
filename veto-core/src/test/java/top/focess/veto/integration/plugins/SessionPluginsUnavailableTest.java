package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.PluginLifecycle;
import top.focess.veto.session.SessionHistoryLoader;

class SessionPluginsUnavailableTest {
    @Test
    void missingPluginPreservesPinWithoutLockingSession() {
        var manager = mock(PluginManager.class);
        var sessions = mock(SessionRepository.class);
        var session = new SessionEntity("owner", "session", "D:/workspace");
        var pin = new PluginBinding("missing.plugin", "1.0.0", "revision");
        session.setPluginBindings(List.of(pin));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(manager.plugin("missing.plugin"))
                .thenThrow(new IllegalArgumentException("Plugin is absent"));

        var selected = new SessionPlugins(manager, sessions, mock(SessionHistoryLoader.class));
        assertEquals(List.of(pin), selected.bindings(session.getId()));
        assertEquals(
                List.of(
                        new SessionPlugins.BoundPluginStatus(
                                "missing.plugin",
                                "1.0.0",
                                "revision",
                                false,
                                SessionPlugins.BoundPluginAvailability.ABSENT)),
                selected.status(session.getId()));
        assertFalse(selected.includes(session.getId(), "missing.plugin"));
    }

    @Test
    void reportsDisabledDeclinedAndRevisionMismatchSeparately() {
        var manager = mock(PluginManager.class);
        var sessions = mock(SessionRepository.class);
        var session = new SessionEntity("owner", "session", "D:/workspace");
        var disabled = new PluginBinding("disabled.plugin", "1.0.0", "1.0.0");
        var declined = new PluginBinding("declined.plugin", "1.0.0", "1.0.0");
        var changed = new PluginBinding("changed.plugin", "1.0.0", "old-revision");
        session.setPluginBindings(List.of(disabled, declined, changed));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(manager.isDisabled("disabled.plugin")).thenReturn(true);
        when(manager.isDeclined("declined.plugin")).thenReturn(true);
        var runtime = mock(PluginLifecycle.class);
        when(runtime.state()).thenReturn(PluginState.ACTIVE);
        when(runtime.identity()).thenReturn(new PluginIdentity("changed.plugin", "1.0.0"));
        when(manager.plugin("changed.plugin")).thenReturn(runtime);

        var selected = new SessionPlugins(manager, sessions, mock(SessionHistoryLoader.class));

        assertEquals(
                List.of(
                        SessionPlugins.BoundPluginAvailability.DISABLED,
                        SessionPlugins.BoundPluginAvailability.DECLINED,
                        SessionPlugins.BoundPluginAvailability.REVISION_MISMATCH),
                selected.status(session.getId()).stream()
                        .map(SessionPlugins.BoundPluginStatus::availability)
                        .toList());
        assertTrue(
                selected.status(session.getId()).stream()
                        .noneMatch(SessionPlugins.BoundPluginStatus::available));
    }
}
