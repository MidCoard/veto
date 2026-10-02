package top.focess.veto.event;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.event.BeforeInputEvent;
import top.focess.veto.api.event.Event;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;

class EventManagerTest {
    @Test
    void emptyListenerPublicationDoesNotResolveAMissingSession() {
        var plugins = mock(PluginManager.class);
        var selections = mock(SessionPlugins.class);
        var publication = mock(PluginManager.PublishedState.class);
        var routes = mock(EventListenerRegistry.class);
        when(plugins.snapshot()).thenReturn(publication);
        when(publication.events()).thenReturn(routes);
        var events = new EventManager(plugins, selections);

        var event =
                new BeforeInputEvent(
                        new Scope.AgentScope("owner", "missing-session", "agent"),
                        () -> false,
                        "text");
        events.submit(event);

        verifyNoInteractions(selections);
        verify(routes).hasHandlers(event);
        verifyNoMoreInteractions(routes);
    }

    @Test
    void routeRejectionOccursBeforeSessionSelection() {
        var plugins = mock(PluginManager.class);
        var selections = mock(SessionPlugins.class);
        var publication = mock(PluginManager.PublishedState.class);
        var routes = mock(EventListenerRegistry.class);
        when(plugins.snapshot()).thenReturn(publication);
        when(publication.events()).thenReturn(routes);
        var event = new SessionNotification(new Scope.SessionScope("owner", "session"));
        when(routes.hasHandlers(event))
                .thenThrow(new IllegalArgumentException("Unregistered event type"));
        var events = new EventManager(plugins, selections);

        assertThrows(IllegalArgumentException.class, () -> events.submit(event));

        verifyNoInteractions(selections);
        verify(routes).hasHandlers(event);
        verifyNoMoreInteractions(routes);
    }

    @Test
    void selectionAndDeliveryUseTheSamePublicationDuringReplacement() {
        var plugins = mock(PluginManager.class);
        var selections = mock(SessionPlugins.class);
        var captured = mock(PluginManager.PublishedState.class);
        var replacement = mock(PluginManager.PublishedState.class);
        var routes = mock(EventListenerRegistry.class);
        when(plugins.snapshot()).thenReturn(captured, replacement);
        when(captured.events()).thenReturn(routes);
        when(routes.hasHandlers(any())).thenReturn(true);
        var selected = Set.of("selected.plugin");
        when(selections.selectedIds("session", captured)).thenReturn(selected);
        var events = new EventManager(plugins, selections);
        var event =
                new BeforeInputEvent(
                        new Scope.AgentScope("owner", "session", "agent"), () -> false, "text");

        events.submit(event);

        verify(plugins).snapshot();
        verify(selections).selectedIds("session", captured);
        verify(routes).submit(event, selected);
        verifyNoInteractions(replacement);
    }

    private static final class SessionNotification extends Event {
        private final Scope.@NonNull SessionScope scope;

        private SessionNotification(Scope.@NonNull SessionScope scope) {
            super(Recipients.SESSION_PLUGINS, FailurePolicy.CONTINUE);
            this.scope = scope;
        }

        @Override
        public Scope.@NonNull SessionScope scope() {
            return scope;
        }
    }

    @Test
    void nonWorkflowNotificationUsesSessionSelectionByItsDeclaredContract() {
        var plugins = mock(PluginManager.class);
        var selections = mock(SessionPlugins.class);
        var publication = mock(PluginManager.PublishedState.class);
        var routes = mock(EventListenerRegistry.class);
        when(plugins.snapshot()).thenReturn(publication);
        when(publication.events()).thenReturn(routes);
        when(routes.hasHandlers(any())).thenReturn(true);
        var selected = Set.of("selected.plugin");
        when(selections.selectedIds("session", publication)).thenReturn(selected);
        var event = new SessionNotification(new Scope.SessionScope("owner", "session"));

        new EventManager(plugins, selections).submit(event);

        verify(selections).selectedIds("session", publication);
        verify(routes).submit(event, selected);
    }

    @Test
    void sessionScopedLifecycleFactKeepsItsExplicitAllActiveRecipients() {
        var plugins = mock(PluginManager.class);
        var selections = mock(SessionPlugins.class);
        var publication = mock(PluginManager.PublishedState.class);
        var routes = mock(EventListenerRegistry.class);
        when(plugins.snapshot()).thenReturn(publication);
        when(publication.events()).thenReturn(routes);
        var event = new SessionDeletedEvent(new Scope.SessionScope("owner", "session"));
        when(routes.hasHandlers(event)).thenReturn(true);

        new EventManager(plugins, selections).submit(event);

        verifyNoInteractions(selections);
        verify(routes).submit(event, null);
    }
}
