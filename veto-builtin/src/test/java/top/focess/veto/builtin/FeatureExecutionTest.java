package top.focess.veto.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.capability.MonitorCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.web.*;
import top.focess.veto.builtin.monitor.MonitorTools;
import top.focess.veto.builtin.web.WebReadSession;

class FeatureExecutionTest {
    @Test
    void readerToolsProcessAndValidateDocumentWithoutCore() {
        var completed = new AtomicInteger();
        try (var reader =
                new WebReadSession(
                        new ReaderSession.Runtime() {
                            public void authorize(@NonNull String operation) {}

                            public @NonNull FetchedPage fetch() {
                                return new FetchedPage(
                                        URI.create("https://example.com/docs"),
                                        200,
                                        "text/plain",
                                        "The timeout is 30 seconds.",
                                        false,
                                        1000);
                            }

                            public @NonNull Execution execution() {
                                return new Execution("reader", "model", 10, 2, 20, 10);
                            }

                            public void completed() {
                                completed.incrementAndGet();
                            }
                        })) {
            assertEquals(
                    List.of("fetch_page", "read_sections", "find_sections", "finish_read"),
                    reader.tools().stream().map(t -> t.getName()).toList());
            reader.setObservationBudget(4096);
            assertTrue(reader.fetchPage().contains("outline"));
            var finish = new FinishReadArgs("complete", "30 seconds", List.of("s1"), List.of());
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () -> reader.finish(finish));
            assertTrue(reader.readSections(List.of("s1")).contains("30 seconds"));
            assertTrue(reader.finish(finish).contains("30 seconds"));
            assertEquals(1, completed.get());
            var result = reader.result();
            if (result == null) throw new AssertionError("Reader did not finish");
            assertEquals("complete", result.outcome());
        }
    }

    @Test
    void monitorToolOwnsTimeValidationAndOutput() {
        var calls = new AtomicInteger();
        MonitorCapability storage =
                new MonitorCapability() {
                    public @NonNull Object create(@NonNull String purpose, @NonNull Instant due) {
                        calls.incrementAndGet();
                        assertTrue(due.isAfter(Instant.now()));
                        return Map.of("purpose", purpose);
                    }

                    public @NonNull Object inspect() {
                        return List.of();
                    }

                    public @NonNull Object control(@NonNull String id, @NonNull String operation) {
                        return Map.of("id", id);
                    }
                };
        var tool = new MonitorTools.CreateMonitor();
        assertThrows(
                ToolDocs.nonNullClass(ToolExecutionException.class),
                () ->
                        tool.execute(
                                new MonitorTools.CreateMonitor.Args("check", null, null), storage));
        assertEquals(0, calls.get());
        assertTrue(
                tool.execute(new MonitorTools.CreateMonitor.Args("check", 60L, null), storage)
                        .contains("check"));
        assertEquals(1, calls.get());
    }
}
