package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.plugin.runtime.ManagedPlugin;

@DefaultQualifier(NonNull.class)
class PluginCatalogueAccessTest {
    @Test
    void sharedAliasesAreExplicitAndInactivePluginCannotRead(@TempDir @NonNull Path root) {
        var plugin = mock(ManagedPlugin.class);
        var storage = mock(PluginStorage.class);
        when(plugin.state()).thenReturn(PluginState.ACTIVE);
        var access =
                new PluginCatalogueAccess(plugin, storage, Map.of("catalogue", root.toString()));
        assertTrue(access.shared("forged").isEmpty());
        var held = access.shared("catalogue").orElseThrow();
        when(plugin.state()).thenReturn(PluginState.CLOSED);
        assertThrows(SecurityException.class, () -> held.files("", "SKILL.md"));
    }

    @Test
    void unselectedSessionCannotObtainWorkspaceTree() {
        var plugin = mock(ManagedPlugin.class);
        var storage = mock(PluginStorage.class);
        when(plugin.state()).thenReturn(PluginState.ACTIVE);
        when(plugin.bindingId()).thenReturn("binding");
        var empty = ToolExecutionPermit.empty();
        var permit =
                new ToolExecutionPermit(
                        empty.call(),
                        empty.capability(),
                        "binding",
                        null,
                        empty.filesystemPaths(),
                        empty.workspaceRoots(),
                        empty.executionRoot(),
                        empty.deployerPolicy(),
                        empty.protectedPaths(),
                        null);
        var access = new PluginCatalogueAccess(plugin, storage, Map.of());
        assertThrows(SecurityException.class, access::workspace);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent",
                        UUID.randomUUID(),
                        "owner",
                        UUID.randomUUID(),
                        ToolResultPresentationMode.BASIC,
                        permit));
        when(storage.currentSession()).thenThrow(new SecurityException("Unselected"));
        try {
            assertThrows(SecurityException.class, access::workspace);
        } finally {
            ToolCallContextHolder.clear();
        }
    }
}
