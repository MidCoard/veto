package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.TextEmbedding;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.plugin.runtime.ManagedPlugin;

class PluginTextEmbeddingsTest {
    @AfterEach
    void clear() {
        ToolCallContextHolder.clear();
        Thread.interrupted();
    }

    private void bind(
            @NonNull String plugin, @NonNull String actualOwner, @NonNull String actualCall) {
        var user = UUID.randomUUID();
        var session = UUID.randomUUID();
        var permit =
                new ToolExecutionPermit(
                                new ToolCall("operation", Map.of(), "call"),
                                ToolCapability.PLUGIN_LOCAL,
                                plugin,
                                null,
                                Map.of(),
                                List.of(),
                                null,
                                DeployerPolicy.FULL_ACCESS,
                                Set.of(),
                                null)
                        .withCaller("agent", user, "owner", session);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent",
                        user,
                        actualOwner,
                        session,
                        ToolResultPresentationMode.BASIC,
                        permit));
        ReflectionTestUtils.invokeMethod(
                ToolCallContextHolder.class, "setCurrentCallId", actualCall);
    }

    @Test
    void requiresSameActivePluginCallerAndExactCall() {
        var plugin = mock(ManagedPlugin.class);
        var model = mock(ToolDocs.nonNullClass(TextEmbedding.class));
        when(plugin.state()).thenReturn(PluginState.ACTIVE);
        when(plugin.bindingId()).thenReturn("instance");
        when(model.dimension()).thenReturn(2);
        when(model.embed("text")).thenReturn(new float[] {1, 0});
        var port = new PluginTextEmbeddings(plugin, model);
        assertThrows(SecurityException.class, () -> port.embed("text"));
        bind("other", "owner", "call");
        assertThrows(SecurityException.class, () -> port.embed("text"));
        bind("instance", "wrong-owner", "call");
        assertThrows(SecurityException.class, () -> port.embed("text"));
        bind("instance", "owner", "expired");
        assertThrows(SecurityException.class, () -> port.embed("text"));
        verifyNoInteractions(model);
        bind("instance", "owner", "call");
        assertArrayEquals(new float[] {1, 0}, port.embed("text"));
        when(plugin.state()).thenReturn(PluginState.CLOSED);
        assertThrows(SecurityException.class, () -> port.embed("text"));
        verify(model, times(1)).embed("text");
    }

    @Test
    void rejectsOversizeAndCancellationBeforeModelAccess() {
        var plugin = mock(ManagedPlugin.class);
        var model = mock(ToolDocs.nonNullClass(TextEmbedding.class));
        when(plugin.state()).thenReturn(PluginState.ACTIVE);
        when(plugin.bindingId()).thenReturn("instance");
        var port = new PluginTextEmbeddings(plugin, model);
        bind("instance", "owner", "call");
        assertThrows(IllegalArgumentException.class, () -> port.embed("x".repeat(64_001)));
        Thread.currentThread().interrupt();
        assertThrows(CancellationException.class, () -> port.embed("text"));
        verifyNoInteractions(model);
    }
}
