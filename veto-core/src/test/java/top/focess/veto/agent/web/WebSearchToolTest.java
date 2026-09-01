package top.focess.veto.agent.web;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpTimeoutException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolExecutionException;

class WebSearchToolTest {

    @Test
    void timeoutReturnsCanonicalUnsuccessfulObservation() throws Exception {
        WebSearchTool tool = new WebSearchTool(new TimeoutProvider());

        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                tool.execute(
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
        WebSearchTool tool = new WebSearchTool(provider);

        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () -> tool.execute(new WebSearchTool.Args("search query", null, null)));

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
