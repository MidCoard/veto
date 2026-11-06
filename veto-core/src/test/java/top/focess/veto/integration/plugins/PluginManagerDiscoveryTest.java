package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolEngineImpl;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.bus.SessionActions;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.*;

/** Manifest discovery and fail-closed Secret Agent host wiring. */
class PluginManagerDiscoveryTest {
    @Test
    void constructorOwnsFrontendInvalidationOnPluginShutdown() throws Exception {
        var sessions = mock(SessionRepository.class);
        var invalidations = mock(SessionActions.class);
        var session =
                new SessionEntity(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), "session");
        try (var plugins =
                new PluginManager(
                        PluginTestSupport.pluginPackages(),
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(PluginTestSupport.configurationServices(null)),
                        new PluginConfigurations(),
                        PluginTestSupport.activationStore(PluginTestSupport.pluginPackages()),
                        PluginTestSupport.providerOf(mock(SessionPlugins.class)),
                        PluginTestSupport.providerOf(sessions),
                        PluginTestSupport.providerOf(invalidations),
                        PluginTestSupport.providerOf(mock(ToolEngineImpl.class)),
                        PluginTestSupport.providerOf(mock(PluginLlmProviders.class)))) {
            session.setPluginBindings(
                    List.of(plugins.registry().plugin("top.focess.builtin").binding()));
            when(sessions.findAll()).thenReturn(List.of(session));
        }
        verify(invalidations).changed(UUID.fromString(session.getId()), "plugin-frontend");
    }

    @Test
    void classpathDoesNotInstallPluginsWithoutManifests() throws Exception {
        try (var plugins =
                PluginTestSupport.manager(
                        "",
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(PluginTestSupport.configurationServices(null)),
                        new PluginConfigurations())) {
            assertTrue(plugins.registry().plugins().isEmpty());
        }
    }

    @Test
    void manifestDiscoversTheSecretProtectionPlugin() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            var plugin = plugins.registry().plugin("top.focess.secret-protection");
            assertSame(plugin, plugins.registry().plugin("org.veto.secret-protection"));
            assertEquals(PluginState.ACTIVE, plugin.state());
            assertTrue(
                    plugins.registry().entries(StandardContributionPoints.OBSERVATION).stream()
                            .noneMatch(
                                    entry ->
                                            entry.implementation()
                                                    .getClass()
                                                    .getName()
                                                    .startsWith("top.focess.veto.secret.")));
            assertFalse(plugins.registry().entries(StandardContributionPoints.LISTENERS).isEmpty());
            assertFalse(plugins.registry().entries(StandardContributionPoints.TOOLS).isEmpty());
        }
    }

    @Test
    void inputPublicationFailsClosedWithoutAnExecutableAgentHost() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            var scope = new Scope.AgentScope(UUID.randomUUID(), "session", "primary");
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            PluginTestSupport.protect(
                                    plugins,
                                    BeforeTextCommitEvent.Phase.INPUT,
                                    scope,
                                    "input",
                                    "password=synthetic-token"));
        }
    }

    @Test
    void secretPluginDoesNotInstallAnUnscopedMaskingFloor() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            String content = "password=synthetic-token";
            assertEquals(content, plugins.applyObservationMiddleware(content));
            var names =
                    plugins.registry().entries(StandardContributionPoints.TOOLS).stream()
                            .map(entry -> entry.implementation())
                            .flatMap(
                                    value ->
                                            value instanceof CapabilityTool<?> tool
                                                    ? Stream.of(tool.getName())
                                                    : Stream.empty())
                            .toList();
            assertTrue(names.contains("import_credential"));
            assertTrue(names.contains("read_credential"));
            assertFalse(names.contains("import_detected_credential"));
        }
    }
}
