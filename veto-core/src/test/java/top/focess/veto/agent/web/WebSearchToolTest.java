package top.focess.veto.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.net.http.HttpTimeoutException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.capability.NetworkEgressCapabilityImpl;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.agent.tool.ToolSecurity;

class WebSearchToolTest {

    @Test
    void anonymousSearchIsElevatedByDefault() {
        ToolSecurity security =
                ToolDocs.nonNullClass(WebSearchTool.class)
                        .getAnnotation(ToolDocs.nonNullClass(ToolSecurity.class));
        if (security == null) {
            throw new AssertionError("web_search must declare @ToolSecurity");
        }

        assertEquals(Danger.ELEVATED, security.defaultDanger());
    }

    @Test
    void timeoutReturnsCanonicalUnsuccessfulObservation() throws Exception {
        WebSearchTool tool =
                new WebSearchTool(
                        new NetworkEgressCapabilityImpl(
                                new TimeoutProvider(),
                                mock(ToolDocs.nonNullClass(WebReader.class)),
                                5,
                                1000,
                                false));

        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                CapabilityTestCalls.execute(
                                        tool,
                                        new WebSearchTool.Args(
                                                "current Java release", null, null)));

        String message = ToolErrors.normalize(error.getMessage());
        assertTrue(message.contains("timed out"));
        assertTrue(message.contains("timeout-test"));
    }

    @Test
    void providerFailureEscapesItsMessageAsJson() throws Exception {
        SearchProvider provider =
                new SearchProvider() {
                    @Override
                    public @NonNull List<SearchResult> search(
                            @NonNull String query, @NonNull SearchOptions options) {
                        throw new IllegalStateException("bad \"response\"\\payload");
                    }

                    @Override
                    public @NonNull String name() {
                        return "broken-test";
                    }
                };
        WebSearchTool tool =
                new WebSearchTool(
                        new NetworkEgressCapabilityImpl(
                                provider,
                                mock(ToolDocs.nonNullClass(WebReader.class)),
                                5,
                                1000,
                                false));

        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                CapabilityTestCalls.execute(
                                        tool, new WebSearchTool.Args("search query", null, null)));

        assertTrue(ToolErrors.normalize(error.getMessage()).contains("bad \"response\"\\payload"));
    }

    private static final class TimeoutProvider implements SearchProvider {

        @Override
        public @NonNull List<SearchResult> search(
                @NonNull String query, @NonNull SearchOptions options) throws HttpTimeoutException {
            throw new HttpTimeoutException("request timed out");
        }

        @Override
        public @NonNull String name() {
            return "timeout-test";
        }
    }
}
