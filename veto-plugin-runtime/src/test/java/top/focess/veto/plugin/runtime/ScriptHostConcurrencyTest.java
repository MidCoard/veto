package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ScriptHostConcurrencyTest {
    @Test
    void queuedInvocationCannotStartAWorkerAfterClose() throws Exception {
        var host = new ScriptHost(Path.of("missing-node-for-closed-host"), 5000);
        try {
            host.register("plugin", () -> {});
            var field = ScriptHost.class.getDeclaredField("lock");
            field.setAccessible(true);
            var lock = assertInstanceOf(ReentrantLock.class, field.get(host));
            var result = new CompletableFuture<Throwable>();
            var caller =
                    Thread.ofVirtual()
                            .unstarted(
                                    () -> {
                                        try {
                                            host.invoke(
                                                    "plugin",
                                                    Path.of("unused.mjs"),
                                                    "unused",
                                                    ScriptPlugin.JSON.createObjectNode());
                                            result.complete(
                                                    new AssertionError(
                                                            "closed invocation succeeded"));
                                        } catch (Throwable failure) {
                                            result.complete(failure);
                                        }
                                    });
            lock.lock();
            try {
                caller.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!lock.hasQueuedThread(caller) && System.nanoTime() < deadline)
                    Thread.onSpinWait();
                assertTrue(
                        lock.hasQueuedThread(caller),
                        "invoke passed registration and waits for exchange");
                host.close();
                assertThrows(IllegalStateException.class, () -> host.register("late", () -> {}));
            } finally {
                lock.unlock();
            }
            var failure = assertInstanceOf(IOException.class, result.get(2, TimeUnit.SECONDS));
            assertEquals("Script host is closed", failure.getMessage());
            assertEquals(-1, host.processId());
            assertFalse(host.registered("late"));
        } finally {
            host.close();
        }
    }

    @Test
    void failedListenerCannotSkipOtherRegistrationsAndFatalPropagatesAfterAllSignals()
            throws Exception {
        for (boolean fatal : new boolean[] {false, true}) {
            try (var host = new ScriptHost(Path.of("unused-node"), 1000)) {
                var signalled = new AtomicInteger();
                var failure =
                        fatal
                                ? new InternalError("fatal listener")
                                : new AssertionError("ordinary listener");
                host.register(
                        "broken",
                        () -> {
                            throw failure;
                        });
                host.register("remaining", signalled::incrementAndGet);
                var failAll = ScriptHost.class.getDeclaredMethod("failAll");
                failAll.setAccessible(true);
                if (fatal) {
                    var invocation =
                            assertThrows(
                                    InvocationTargetException.class, () -> failAll.invoke(host));
                    var cause = invocation.getCause();
                    if (cause == null) throw new AssertionError("Missing listener failure cause");
                    assertSame(failure, cause);
                } else failAll.invoke(host);
                assertEquals(1, signalled.get());
                assertFalse(host.registered("broken"));
                assertFalse(host.registered("remaining"));
                failAll.invoke(host);
                assertEquals(1, signalled.get());
            }
        }
    }
}
