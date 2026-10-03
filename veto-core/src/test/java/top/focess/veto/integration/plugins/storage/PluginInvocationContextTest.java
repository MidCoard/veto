package top.focess.veto.integration.plugins.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.llm.ToolResultPresentationMode;

class PluginInvocationContextTest {
    @Test
    void nestedCallbacksRestoreAfterNormalAndExceptionalExit() {
        assertNull(PluginInvocationContext.currentSession());
        var outer = new PluginInvocationContext("owner", "outer");
        try {
            assertEquals("outer", PluginInvocationContext.currentSession());
            var inner = new PluginInvocationContext("other", "inner");
            try {
                assertEquals("inner", PluginInvocationContext.currentSession());
            } finally {
                inner.close();
            }
            assertEquals("outer", PluginInvocationContext.currentSession());
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        var exceptional = new PluginInvocationContext("other", "exceptional");
                        try {
                            assertEquals("exceptional", PluginInvocationContext.currentSession());
                            throw new IllegalStateException("test failure");
                        } finally {
                            exceptional.close();
                        }
                    });
            assertEquals("outer", PluginInvocationContext.currentSession());
        } finally {
            outer.close();
        }
        assertNull(PluginInvocationContext.currentSession());
    }

    @Test
    void callbackContextDoesNotInheritAcrossThreads() throws Exception {
        var invocation = new PluginInvocationContext("owner", "parent");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var child =
                    executor.submit(
                            () -> {
                                assertNull(PluginInvocationContext.currentSession());
                                var nested = new PluginInvocationContext("child", "child-session");
                                try {
                                    assertEquals(
                                            "child-session",
                                            PluginInvocationContext.currentSession());
                                } finally {
                                    nested.close();
                                }
                                assertNull(PluginInvocationContext.currentSession());
                            });
            child.get(5, TimeUnit.SECONDS);
            assertEquals("parent", PluginInvocationContext.currentSession());
        } finally {
            invocation.close();
        }
        assertNull(PluginInvocationContext.currentSession());
    }

    @Test
    void callbackSessionOverridesToolFallbackAndRestoresIt() {
        var session = UUID.randomUUID();
        var permit = mock(ToolExecutionPermit.class);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent",
                        UUID.randomUUID(),
                        "owner",
                        session,
                        ToolResultPresentationMode.BASIC,
                        permit));
        try {
            assertEquals(session.toString(), PluginInvocationContext.currentSession());
            var invocation = new PluginInvocationContext("other", "callback");
            try {
                assertEquals("callback", PluginInvocationContext.currentSession());
            } finally {
                invocation.close();
            }
            assertEquals(session.toString(), PluginInvocationContext.currentSession());
        } finally {
            ToolCallContextHolder.clear();
        }
        assertNull(PluginInvocationContext.currentSession());
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent",
                        UUID.randomUUID(),
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        permit));
        try {
            assertNull(PluginInvocationContext.currentSession());
        } finally {
            ToolCallContextHolder.clear();
        }
    }
}
