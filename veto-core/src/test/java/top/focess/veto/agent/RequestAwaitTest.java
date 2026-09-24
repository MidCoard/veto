package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.workflow.PluginAwait;

class RequestAwaitTest {
    @Test
    void unsuccessfulToolCallDiscardsItsUnacceptedWait() {
        @NonNull ToolCallContext context = mock();
        when(context.requestId()).thenReturn("request");
        var signal = new CompletableFuture<Boolean>();
        ToolCallContextHolder.set(context);
        ToolCallContextHolder.await(new PluginAwait("uncommitted", signal));
        ToolCallContextHolder.clear();
        assertTrue(signal.isCancelled());
    }

    @Test
    void signalResumesOnlyItsOwnerAndIsConsumedOnce() {
        var first = new RequestHandle(new Object());
        var second = new RequestHandle(new Object());
        var signal = new CompletableFuture<Boolean>();
        var wakes = new AtomicInteger();
        first.await(new PluginAwait("plugin-generation/delivery", signal), wakes::incrementAndGet);
        assertTrue(first.awaiting());
        assertFalse(second.awaiting());
        assertTrue(signal.complete(true));
        assertFalse(signal.complete(true));
        assertEquals(1, wakes.get());
        assertTrue(first.readyToResume());
        assertFalse(first.awaiting());
        assertFalse(first.readyToResume());
    }

    @Test
    void closeCancelsSignalsAndPluginFailureIsObservable() {
        var request = new RequestHandle(new Object());
        var cancelled = new CompletableFuture<Boolean>();
        request.await(new PluginAwait("cancel", cancelled), () -> {});
        request.releaseWaits();
        assertTrue(cancelled.isCancelled());
        var failed = new CompletableFuture<Boolean>();
        request.await(new PluginAwait("failure", failed), () -> {});
        failed.completeExceptionally(new IllegalStateException("Plugin stopped"));
        assertThrows(CompletionException.class, request::awaiting);
        request.releaseWaits();
        assertFalse(request.awaiting());
    }
}
