package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.veto.LlamaCppBridge;

/** Ingress composition; the Secret plugin's Agent workflow has its own integration tests. */
class IngressDefenseMaskingTest {
    @Test
    void scopedProtectionHandlesSuccessfulAndFailedObservationsEvenWithoutMaskFlag() {
        var plugins = mock(PluginManager.class);
        var defense = new IngressDefense(null, plugins);
        String content = "contact person@example.com password=synthetic";
        for (var result :
                new ToolResult[] {
                    ToolResult.success("read_file", "c1", content),
                    ToolResult.failure(
                            "read_file", "c1", content, ToolErrorCode.GENERIC.TOOL_FAILURE)
                }) {
            var protectedText =
                    defense.maskAndFrame(
                            call(),
                            readToolDef(),
                            result,
                            false,
                            new ReadHistory(),
                            text -> text.replace("synthetic", "cred_example"));
            assertEquals("contact person@example.com password=cred_example", protectedText);
        }
        verifyNoInteractions(plugins);
    }

    @Test
    void scopedFailureCannotPublishOriginalContent() {
        var defense = new IngressDefense(null, mock(PluginManager.class));
        assertThrows(
                IllegalStateException.class,
                () ->
                        defense.maskAndFrame(
                                call(),
                                readToolDef(),
                                ToolResult.success("read_file", "c1", "secret"),
                                true,
                                new ReadHistory(),
                                text -> {
                                    throw new IllegalStateException("Protection unavailable");
                                }));
    }

    @Test
    void trustedUnchangedReplacementDoesNotFallBackToUnscopedMasking() {
        var plugins = mock(PluginManager.class);
        var defense = new IngressDefense(null, plugins);
        assertEquals(
                "authorized value",
                defense.maskAndFrame(
                        call(),
                        readToolDef(),
                        ToolResult.success("read_file", "c1", "authorized value"),
                        true,
                        new ReadHistory(),
                        text -> text));
        verifyNoInteractions(plugins);
    }

    @Test
    void advisorySignalCannotBypassASelectedMaskingContribution() {
        var plugins = mock(PluginManager.class);
        when(plugins.applyObservationMiddleware(anyString())).thenReturn("masked");
        var bridge = mock(LlamaCppBridge.class);
        when(bridge.isAvailable()).thenReturn(true);
        when(bridge.infer(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture("{\"risk\":\"high\"}"));
        var defense = new IngressDefense(new SemanticMasker(bridge, plugins), plugins);
        assertEquals(
                "masked",
                defense.maskAndFrame(
                        call(),
                        readToolDef(),
                        ToolResult.success("read_file", "c1", "api_key=raw"),
                        true,
                        new ReadHistory()));
        verify(bridge).infer(anyString(), anyString());
        verify(plugins).applyObservationMiddleware("api_key=raw");
    }

    private static @NonNull NativeToolDefinition readToolDef() {
        return new NativeToolDefinition(
                "read_file",
                "Read a file",
                ToolCapability.WORKSPACE_READ,
                Danger.SAFE,
                false,
                Object.class,
                Void.class,
                Map.of("path", ParamCategory.FILESYSTEM_PATH));
    }

    private static @NonNull ToolCall call() {
        return new ToolCall("read_file", Map.of("path", "/fixture"), "c1");
    }
}
