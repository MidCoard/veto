package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.RemoteTool;
import top.focess.veto.api.agent.tool.Tool;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.contribution.ContributionEntry;
import top.focess.veto.api.plugin.contribution.ContributionSource;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.plugin.runtime.PluginLifecycle;
import top.focess.veto.vault.UserContext;

class PluginControllerTest {
    @Test
    void listsHumanNameAndBothToolKinds() {
        var manager = mock(PluginManager.class);
        var catalog = mock(ContributionCatalog.class);
        var lifecycle = mock(PluginLifecycle.class);
        var implementation = mock(VetoPlugin.class);
        var identity = new PluginIdentity("example.tools", "1.0.0");
        var source =
                new ContributionSource(
                        identity.id(), identity.version(), ContributionSource.Origin.PLUGIN);
        var portable = source.qualify("portable");
        var nativeTool = source.qualify("native");
        ContributionEntry<Tool> portableEntry =
                new ContributionEntry<>(portable, source, mock(RemoteTool.class));
        NativeTool<?> nativeImplementation = mock(NativeTool.class);
        ContributionEntry<Tool> nativeEntry =
                new ContributionEntry<>(nativeTool, source, nativeImplementation);
        when(manager.plugins()).thenReturn(List.of(lifecycle));
        when(manager.registrations()).thenReturn(List.of());
        when(manager.catalog()).thenReturn(catalog);
        when(lifecycle.identity()).thenReturn(identity);
        when(lifecycle.state()).thenReturn(PluginState.ACTIVE);
        when(lifecycle.implementation()).thenReturn(implementation);
        when(lifecycle.displayName()).thenReturn("Example Tools");
        when(catalog.entries(StandardContributionPoints.TOOLS))
                .thenReturn(List.of(portableEntry, nativeEntry));
        when(manager.toolName(portableEntry)).thenReturn("portable_alias");
        when(manager.toolName(nativeEntry)).thenReturn("native_alias");

        UserContext.set("admin");
        try {
            var response =
                    new PluginController(manager, new RequestAuthorization("admin"::equals)).list();
            assertEquals(1, response.size());
            assertEquals("Example Tools", response.getFirst().name());
            assertEquals(List.of("native_alias", "portable_alias"), response.getFirst().tools());
        } finally {
            UserContext.clear();
        }
    }
}
