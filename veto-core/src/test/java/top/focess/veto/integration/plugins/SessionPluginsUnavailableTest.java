package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.Tool;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.PluginDeclinedException;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionEntry;
import top.focess.veto.api.plugin.contribution.ContributionSource;
import top.focess.veto.event.EventHandlerCompiler;
import top.focess.veto.event.EventListenerRegistry;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.InstalledPluginLoader;
import top.focess.veto.plugin.runtime.ManagedPlugin;

class SessionPluginsUnavailableTest {
    @Test
    void missingPluginPreservesPinWithoutLockingSession() {
        var manager = mock(PluginManager.class);
        publication(manager, List.of());
        var sessions = mock(SessionRepository.class);
        var session =
                new SessionEntity(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                        "session",
                        "D:/workspace");
        var pin = new PluginBinding("missing.plugin", "1.0.0", "revision");
        session.setPluginBindings(List.of(pin));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));

        var selected = new SessionPlugins(manager, sessions);
        assertEquals(List.of(pin), selected.bindings(session.getId()));
        assertEquals(
                List.of(
                        new SessionPlugins.BoundPluginStatus(
                                "missing.plugin",
                                "missing.plugin",
                                List.of(),
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
        var session =
                new SessionEntity(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                        "session",
                        "D:/workspace");
        var disabled = new PluginBinding("disabled.plugin", "1.0.0", "1.0.0");
        var declined = new PluginBinding("declined.plugin", "1.0.0", "1.0.0");
        var changed = new PluginBinding("changed.plugin", "1.0.0", "old-revision");
        session.setPluginBindings(List.of(disabled, declined, changed));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        var runtime = mock(ManagedPlugin.class);
        when(runtime.displayName()).thenReturn("Changed Plugin");
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
        assertEquals(
                List.of("Disabled", "Declined", "Changed Plugin"),
                selected.status(session.getId()).stream()
                        .map(SessionPlugins.BoundPluginStatus::name)
                        .toList());
        assertTrue(
                selected.status(session.getId()).stream()
                        .allMatch(status -> status.tools().isEmpty()));
        assertTrue(
                selected.status(session.getId()).stream()
                        .noneMatch(SessionPlugins.BoundPluginStatus::available));
    }

    @Test
    void missingBindingsAreRejectedWithoutInferringOrSavingSelection() {
        var manager = mock(PluginManager.class);
        var sessions = mock(SessionRepository.class);
        var session =
                new SessionEntity(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), "uninitialized");
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
    void creationSelectionPinsActiveRevisionAndRejectsUnavailableOrDuplicateAliases() {
        var manager = mock(PluginManager.class);
        doCallRealMethod().when(manager).selection(anyList());
        var installed = mock(ManagedPlugin.class);
        var identity = new PluginIdentity("installed.plugin", "1.0.0");
        var pin = new PluginBinding(identity.id(), identity.version(), "exact-revision");
        when(installed.identity()).thenReturn(identity);
        when(installed.binding()).thenReturn(pin);
        when(installed.state()).thenReturn(PluginState.ACTIVE);
        var publication = publication(manager, List.of(installed));
        when(publication.canonicalId("historical.plugin")).thenReturn(identity.id());

        assertEquals(List.of(), manager.selection(List.of()));
        assertEquals(List.of(pin), manager.selection(List.of("historical.plugin")));
        assertThrows(
                IllegalArgumentException.class,
                () -> manager.selection(List.of(identity.id(), "historical.plugin")));
        assertThrows(
                IllegalArgumentException.class, () -> manager.selection(List.of("missing.plugin")));
        when(installed.state()).thenReturn(PluginState.CLOSED);
        assertThrows(
                IllegalArgumentException.class, () -> manager.selection(List.of(identity.id())));
    }

    @Test
    void explicitEmptySelectionRemainsEmptyAndReadsDoNotWrite() {
        var manager = mock(PluginManager.class);
        var installed = mock(ManagedPlugin.class);
        when(installed.identity()).thenReturn(new PluginIdentity("installed.plugin", "1.0.0"));
        when(installed.displayName()).thenReturn("Installed");
        publication(manager, List.of(installed));
        var sessions = mock(SessionRepository.class);
        var session =
                new SessionEntity(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), "plugin-free");
        session.setPluginBindings(List.of());
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        var selected = new SessionPlugins(manager, sessions);

        assertEquals(List.of(), selected.bindings(session.getId()));
        assertEquals(List.of(), selected.status(session.getId()));
        verify(sessions, times(2)).findById(session.getId());
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void reportsDisplayNameAndOwnToolsThenPreservesBindingWhenPackageIsRemoved() {
        var manager = mock(PluginManager.class);
        var runtime = mock(ManagedPlugin.class);
        var pin = new PluginBinding("workspace.plugin", "1.0.0", "revision");
        when(runtime.identity()).thenReturn(new PluginIdentity(pin.id(), pin.version()));
        when(runtime.binding()).thenReturn(pin);
        when(runtime.displayName()).thenReturn("Workspace Tools");
        when(runtime.state()).thenReturn(PluginState.ACTIVE);
        var registry = publication(manager, List.of(runtime));
        var source = new ContributionSource(pin.id(), "1.0.0", ContributionSource.Origin.PLUGIN);
        var other =
                new ContributionSource("other.plugin", "1.0.0", ContributionSource.Origin.PLUGIN);
        var ownTool = new ContributionEntry<>(source.qualify("read"), source, mock(Tool.class));
        var otherTool = new ContributionEntry<>(other.qualify("read"), other, mock(Tool.class));
        doReturn(List.of(ownTool, otherTool))
                .when(registry)
                .entries(StandardContributionPoints.TOOLS);
        doReturn("read_file").when(registry).toolName(ownTool);
        var sessions = mock(SessionRepository.class);
        var session = new SessionEntity(UUID.randomUUID(), "session");
        session.setPluginBindings(List.of(pin));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        var selected = new SessionPlugins(manager, sessions);
        var status = selected.status(session.getId()).getFirst();
        assertEquals("Workspace Tools", status.name());
        assertEquals(List.of("read_file"), status.tools());
        assertTrue(status.available());
        when(runtime.binding()).thenReturn(new PluginBinding(pin.id(), "1.0.0", "changed"));
        assertTrue(selected.status(session.getId()).getFirst().tools().isEmpty());
        when(manager.registry()).thenReturn(PluginRegistry.empty());
        var missing = selected.status(session.getId()).getFirst();
        assertEquals(SessionPlugins.BoundPluginAvailability.ABSENT, missing.availability());
        assertEquals(pin.id(), missing.name());
        assertTrue(missing.tools().isEmpty());
        assertEquals(List.of(pin), selected.bindings(session.getId()));
    }

    private static @NonNull PluginRegistry publication(
            @NonNull PluginManager manager, @NonNull List<ManagedPlugin> plugins) {
        var builder = new ContributionCatalog.Builder();
        for (var point : StandardContributionPoints.ALL) builder.define(point, ignored -> {});
        var catalog = builder.freeze();
        var owners =
                plugins.stream()
                        .collect(
                                Collectors.toMap(
                                        plugin -> plugin.identity().id(), plugin -> plugin));
        var publication =
                spy(
                        new PluginRegistry(
                                plugins,
                                List.of(),
                                List.of(),
                                catalog,
                                EventListenerRegistry.build(
                                        catalog, owners, new EventHandlerCompiler()),
                                Map.of(),
                                Map.of(),
                                Set.of()));
        when(manager.registry()).thenReturn(publication);
        return publication;
    }
}
