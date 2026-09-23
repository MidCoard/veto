package top.focess.veto.plugin.runtime;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.TextProtection;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.SessionHistoryLoader;

/** Shared wiring for plugin-backed tests: provider stubs plus real ServiceLoader discovery. */
public final class PluginTestSupport {
    private PluginTestSupport() {}

    /** A provider yielding the given value (or none), for ObjectProvider-based wiring in tests. */
    public static <T> @NonNull ObjectProvider<T> providerOf(@Nullable T value) {
        return new ObjectProvider<>() {
            @Override
            public @NonNull T getObject() {
                var result = value;
                if (result == null) throw new NoSuchBeanDefinitionException("empty test provider");
                return result;
            }

            @Override
            public @Nullable T getIfAvailable() {
                return value;
            }

            @Override
            public @NonNull Iterator<T> iterator() {
                return value == null ? Collections.emptyIterator() : List.of(value).iterator();
            }
        };
    }

    /** Starts a manager whose plugins come from ServiceLoader discovery (no script packages). */
    public static @NonNull PluginManager manager() throws IOException {
        return manager(null);
    }

    public static @NonNull PluginManager manager(@Nullable PluginHostServices services)
            throws IOException {
        return new PluginManager("", "", false, 5000, providerOf(services));
    }

    /**
     * Session selection backed by a stub repository; every session selects every discovered plugin.
     */
    public static @NonNull SessionPlugins sessionPlugins(@NonNull PluginManager manager) {
        SessionRepository sessions = mock(ToolDocs.nonNullClass(SessionRepository.class));
        SessionHistoryLoader history = mock(ToolDocs.nonNullClass(SessionHistoryLoader.class));
        var entity = new SessionEntity("owner", "session");
        entity.setPluginBindings(
                manager.plugins().stream()
                        .map(
                                plugin ->
                                        new PluginBinding(
                                                plugin.identity().id(),
                                                plugin.identity().version(),
                                                plugin.identity().version()))
                        .toList());
        when(sessions.findById(anyString())).thenReturn(Optional.of(entity));
        return new SessionPlugins(manager, sessions, history);
    }

    /** Applies the discovered plugins' protection chain outside the session-binding machinery. */
    public static @NonNull String protect(
            @NonNull PluginManager manager,
            @NonNull ContributionPoint<? extends TextProtection> point,
            TextProtection.@NonNull Scope scope,
            @NonNull String sourceId,
            @NonNull String text)
            throws PluginFailure {
        String result = text;
        for (var entry : manager.catalog().entries(point)) {
            String input = result;
            result =
                    manager.plugin(entry.source().namespace())
                            .execute(
                                    () -> entry.implementation().transform(scope, sourceId, input));
        }
        return result;
    }

    /** Reveals a reference through the plugin's frontend "show" action; empty when unavailable. */
    public static @NonNull Optional<String> reveal(
            @NonNull PluginManager manager,
            TextProtection.@NonNull Scope scope,
            @NonNull String reference)
            throws PluginFailure {
        var entry = manager.catalog().entries(StandardContributionPoints.FRONTEND).getFirst();
        JsonValue result =
                manager.plugin(entry.source().namespace())
                        .execute(
                                () ->
                                        entry.implementation()
                                                .handler()
                                                .handle(
                                                        new FrontendContribution.Scope(
                                                                scope.ownerId(),
                                                                scope.sessionId(),
                                                                scope.agentId()),
                                                        "show",
                                                        new JsonValue.ObjectValue(
                                                                Map.of(
                                                                        "reference",
                                                                        new JsonValue.StringValue(
                                                                                reference)))));
        return result instanceof JsonValue.StringValue value
                ? Optional.of(value.value())
                : Optional.empty();
    }
}
