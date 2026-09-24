package top.focess.veto.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.http.ApprovedHttpDestination;
import top.focess.veto.api.http.HttpDocument;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.builtin.monitor.MonitorOperations;
import top.focess.veto.builtin.monitor.MonitorTools;
import top.focess.veto.builtin.web.WebReadSession;
import top.focess.veto.builtin.web.model.*;

class FeatureExecutionTest {
    @Test
    void readerToolsProcessAndValidateDocumentWithoutCore() {
        var completed = new AtomicInteger();
        var authorized = new ArrayList<String>();
        var runtime =
                new IsolatedAgent.Runtime() {
                    public @NonNull String id() {
                        return "reader";
                    }

                    public void authorize(@NonNull String operation) {
                        authorized.add(operation);
                    }

                    public int observationBudgetBytes() {
                        return 4096;
                    }

                    public IsolatedAgent.@NonNull Usage usage() {
                        return new IsolatedAgent.Usage("reader", "model", 10, 2, 20, 10);
                    }

                    public void complete(@NonNull String result) {
                        completed.incrementAndGet();
                    }
                };
        var destination =
                new ApprovedHttpDestination() {
                    public void bind(
                            IsolatedAgent.@NonNull Runtime child, @NonNull String operation) {
                        throw new AssertionError("Session must not rebind its host grant");
                    }

                    public @NonNull HttpDocument fetch() {
                        return new HttpDocument(
                                URI.create("https://example.com/docs"),
                                200,
                                "text/plain",
                                "The timeout is 30 seconds.",
                                false,
                                1000);
                    }

                    public void publish(@NonNull IsolatedAgent child) {
                        throw new AssertionError("Session cannot publish execution provenance");
                    }

                    public void close() {}
                };
        try (var reader = new WebReadSession(runtime, destination)) {
            assertEquals(
                    List.of("fetch_page", "read_sections", "find_sections", "finish_read"),
                    reader.tools().stream().map(t -> t.getName()).toList());
            assertTrue(reader.fetchPage().contains("outline"));
            var finish = new FinishReadArgs("complete", "30 seconds", List.of("s1"), List.of());
            assertThrows(
                    ToolDocs.nonNullClass(ToolExecutionException.class),
                    () -> reader.finish(finish));
            assertTrue(reader.readSections(List.of("s1")).contains("30 seconds"));
            assertTrue(reader.finish(finish).contains("30 seconds"));
            assertEquals(1, completed.get());
            assertTrue(
                    authorized.containsAll(List.of("fetch_page", "read_sections", "finish_read")));
            var result = reader.result();
            if (result == null) throw new AssertionError("Reader did not finish");
            assertEquals("complete", result.outcome());
        }
    }

    @Test
    void monitorToolOwnsTimeValidationAndOutput() {
        var calls = new AtomicInteger();
        MonitorOperations storage =
                new MonitorOperations() {
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
