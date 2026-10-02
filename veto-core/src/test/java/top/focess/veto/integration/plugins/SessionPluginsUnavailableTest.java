package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.PluginDeclinedException;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.InstalledPluginLoader;
import top.focess.veto.plugin.runtime.PluginLifecycle;

class SessionPluginsUnavailableTest {
    @Test
    void missingPluginPreservesPinWithoutLockingSession() {
        var manager = mock(PluginManager.class);
        publication(manager, List.of());
        var sessions = mock(SessionRepository.class);
        var session = new SessionEntity("owner", "session", "D:/workspace");
        var pin = new PluginBinding("missing.plugin", "1.0.0", "revision");
        session.setPluginBindings(List.of(pin));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));

        var selected = new SessionPlugins(manager, sessions);
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
        var runtime = mock(PluginLifecycle.class);
        when(runtime.state()).thenReturn(PluginState.ACTIVE);
        when(runtime.identity()).thenReturn(new PluginIdentity("changed.plugin", "1.0.0"));
        when(runtime.binding()).thenReturn(new PluginBinding("changed.plugin", "1.0.0", "1.0.0"));
        var publication = publication(manager, List.of(runtime));
        when(publication.disabled())
                .thenReturn(
                        List.of(
                                new InstalledPluginLoader.DisabledPackage(
                                        "disabled.plugin", "Disabled", "1.0.0")));
        when(publication.declined())
                .thenReturn(
                        List.of(
                                new PluginManager.DeclinedPlugin(
                                        "declined.plugin",
                                        "Declined",
                                        "1.0.0",
                                        PluginDeclinedException.Reason.NOT_APPLICABLE)));

        var selected = new SessionPlugins(manager, sessions);

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

    @Test
    void missingBindingsAreRejectedWithoutInferringOrSavingSelection() {
        var manager = mock(PluginManager.class);
        var sessions = mock(SessionRepository.class);
        var session = new SessionEntity("owner", "uninitialized");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        var selected = new SessionPlugins(manager, sessions);

        var failure =
                assertThrows(IllegalStateException.class, () -> selected.bindings(session.getId()));
        assertEquals("Session plugin bindings are missing", failure.getMessage());
        assertNull(session.getPluginBindings());
        verify(sessions).findById(session.getId());
        verifyNoMoreInteractions(sessions);
        verifyNoInteractions(manager);
    }

    @Test
    void explicitEmptySelectionRemainsEmptyAndReadsDoNotWrite() {
        var manager = mock(PluginManager.class);
        publication(manager, List.of());
        var sessions = mock(SessionRepository.class);
        var session = new SessionEntity("owner", "plugin-free");
        session.setPluginBindings(List.of());
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        var selected = new SessionPlugins(manager, sessions);

        assertEquals(List.of(), selected.bindings(session.getId()));
        assertEquals(List.of(), selected.status(session.getId()));
        verify(sessions, times(2)).findById(session.getId());
        verifyNoMoreInteractions(sessions);
    }

    private static PluginManager.@NonNull PublishedState publication(
            @NonNull PluginManager manager, @NonNull List<PluginLifecycle> plugins) {
        var builder = new ContributionCatalog.Builder();
        for (var point : StandardContributionPoints.ALL) builder.define(point, ignored -> {});
        var publication = mock(PluginManager.PublishedState.class);
        when(publication.catalog()).thenReturn(builder.freeze());
        when(publication.plugins()).thenReturn(plugins);
        when(publication.disabled()).thenReturn(List.of());
        when(publication.declined()).thenReturn(List.of());
        when(publication.plugin(anyString()))
                .thenAnswer(
                        invocation -> {
                            String id = invocation.getArgument(0);
                            return plugins.stream()
                                    .filter(plugin -> plugin.identity().id().equals(id))
                                    .findFirst()
                                    .orElseThrow(
                                            () -> new IllegalArgumentException("Plugin is absent"));
                        });
        when(manager.canonicalId(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(manager.snapshot()).thenReturn(publication);
        return publication;
    }
}
