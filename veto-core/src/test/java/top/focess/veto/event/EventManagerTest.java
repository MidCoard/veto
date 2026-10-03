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
import top.focess.veto.integration.plugins.PluginRegistry;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.integration.plugins.storage.PluginInvocationContext;

class EventManagerTest {
    @Test
    void emptyListenerPublicationDoesNotResolveAMissingSession() {
        var plugins = mock(PluginManager.class);
        var selections = mock(SessionPlugins.class);
        var publication = mock(PluginRegistry.class);
        var routes = mock(EventListenerRegistry.class);
        when(plugins.registry()).thenReturn(publication);
        when(publication.events()).thenReturn(routes);
        var events = new EventManager(plugins, selections);

        var event =
                new BeforeInputEvent(
                        new Scope.AgentScope("owner", "missing-session", "agent"), "text");
        var context = new PluginInvocationContext("owner", "missing-session");
        try {
            events.submit(event);
        } finally {
            context.close();
        }

        verifyNoInteractions(selections);
        verify(routes).hasHandlers(event);
        verifyNoMoreInteractions(routes);
    }

    @Test
    void routeRejectionOccursBeforeSessionSelection() {
        var plugins = mock(PluginManager.class);
        var selections = mock(SessionPlugins.class);
        var publication = mock(PluginRegistry.class);
        var routes = mock(EventListenerRegistry.class);
        when(plugins.registry()).thenReturn(publication);
        when(publication.events()).thenReturn(routes);
        var event = new SessionNotification(new Scope.SessionScope("owner", "session"));
        when(routes.hasHandlers(event))
                .thenThrow(new IllegalArgumentException("Unregistered event type"));
        var events = new EventManager(plugins, selections);

        var context = new PluginInvocationContext("owner", "session");
        try {
            assertThrows(IllegalArgumentException.class, () -> events.submit(event));
        } finally {
            context.close();
        }

        verifyNoInteractions(selections);
        verify(routes).hasHandlers(event);
        verifyNoMoreInteractions(routes);
    }

    @Test
    void selectionAndDeliveryUseTheSamePublicationDuringReplacement() {
        var plugins = mock(PluginManager.class);
        var selections = mock(SessionPlugins.class);
        var captured = mock(PluginRegistry.class);
        var replacement = mock(PluginRegistry.class);
        var routes = mock(EventListenerRegistry.class);
        when(plugins.registry()).thenReturn(captured, replacement);
        when(captured.events()).thenReturn(routes);
        when(routes.hasHandlers(any())).thenReturn(true);
        var selected = Set.of("selected.plugin");
        when(selections.selectedIds("session", captured)).thenReturn(selected);
        var events = new EventManager(plugins, selections);
        var event = new BeforeInputEvent(new Scope.AgentScope("owner", "session", "agent"), "text");

        var context = new PluginInvocationContext("owner", "session");
        try {
            events.submit(event);
        } finally {
            context.close();
        }

        verify(plugins).registry();
        verify(selections).selectedIds("session", captured);
        verify(routes).submit(event, selected);
        verifyNoInteractions(replacement);
    }

    @Test
    void lifecycleFactInsideSessionContextUsesTheSameSelectionRule() {
        var plugins = mock(PluginManager.class);
        var selections = mock(SessionPlugins.class);
        var publication = mock(PluginRegistry.class);
        var routes = mock(EventListenerRegistry.class);
        when(plugins.registry()).thenReturn(publication);
        when(publication.events()).thenReturn(routes);
        var event = new SessionDeletedEvent(new Scope.SessionScope("owner", "payload-session"));
        when(routes.hasHandlers(event)).thenReturn(true);
        var selected = Set.of("selected.plugin");
        when(selections.selectedIds("ambient-session", publication)).thenReturn(selected);
        var context = new PluginInvocationContext("owner", "ambient-session");
        try {
            new EventManager(plugins, selections).submit(event);
        } finally {
            context.close();
        }
        verify(selections).selectedIds("ambient-session", publication);
        verify(routes).submit(event, selected);
    }

    private static final class SessionNotification extends Event {
        private final Scope.@NonNull SessionScope scope;

        private SessionNotification(Scope.@NonNull SessionScope scope) {
            this.scope = scope;
        }

        @Override
        public Scope.@NonNull SessionScope scope() {
            return scope;
        }
    }

    @Test
    void nonWorkflowNotificationUsesAmbientSessionRegardlessOfItsPayloadScope() {
        var plugins = mock(PluginManager.class);
        var selections = mock(SessionPlugins.class);
        var publication = mock(PluginRegistry.class);
        var routes = mock(EventListenerRegistry.class);
        when(plugins.registry()).thenReturn(publication);
        when(publication.events()).thenReturn(routes);
        when(routes.hasHandlers(any())).thenReturn(true);
        var selected = Set.of("selected.plugin");
        when(selections.selectedIds("session", publication)).thenReturn(selected);
        var event = new SessionNotification(new Scope.SessionScope("owner", "payload-session"));

        var context = new PluginInvocationContext("owner", "session");
        try {
            new EventManager(plugins, selections).submit(event);
        } finally {
            context.close();
        }

        verify(selections).selectedIds("session", publication);
        verify(routes).submit(event, selected);
    }

    @Test
    void sessionScopedPayloadOutsideSessionContextReachesAllActivePlugins() {
        var plugins = mock(PluginManager.class);
        var selections = mock(SessionPlugins.class);
        var publication = mock(PluginRegistry.class);
        var routes = mock(EventListenerRegistry.class);
        when(plugins.registry()).thenReturn(publication);
        when(publication.events()).thenReturn(routes);
        var event = new SessionDeletedEvent(new Scope.SessionScope("owner", "session"));
        when(routes.hasHandlers(event)).thenReturn(true);

        new EventManager(plugins, selections).submit(event);

        verifyNoInteractions(selections);
        verify(routes).submit(event, null);
    }
}
