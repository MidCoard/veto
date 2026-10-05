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
        var outer =
                new PluginInvocationContext(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), "outer");
        try {
            assertEquals("outer", PluginInvocationContext.currentSession());
            var inner =
                    new PluginInvocationContext(
                            UUID.fromString("ede9d700-cf06-5666-9e12-b8cb22e3da12"), "inner");
            try {
                assertEquals("inner", PluginInvocationContext.currentSession());
            } finally {
                inner.close();
            }
            assertEquals("outer", PluginInvocationContext.currentSession());
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        var exceptional =
                                new PluginInvocationContext(
                                        UUID.fromString("ede9d700-cf06-5666-9e12-b8cb22e3da12"),
                                        "exceptional");
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
        var invocation =
                new PluginInvocationContext(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), "parent");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var child =
                    executor.submit(
                            () -> {
                                assertNull(PluginInvocationContext.currentSession());
                                var nested =
                                        new PluginInvocationContext(
                                                UUID.fromString(
                                                        "d3ac078f-2576-5600-8bd8-887f14d2a6ab"),
                                                "child-session");
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
                        session,
                        ToolResultPresentationMode.BASIC,
                        permit));
        try {
            assertEquals(session.toString(), PluginInvocationContext.currentSession());
            var invocation =
                    new PluginInvocationContext(
                            UUID.fromString("ede9d700-cf06-5666-9e12-b8cb22e3da12"), "callback");
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
                        ToolResultPresentationMode.BASIC,
                        permit));
        try {
            assertNull(PluginInvocationContext.currentSession());
        } finally {
            ToolCallContextHolder.clear();
        }
    }
}
