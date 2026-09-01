package top.focess.veto.agent.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolExecutionException;

class GrepSearchToolTest {

    private final @NonNull GrepSearchTool tool = new GrepSearchTool();

    @Test
    void rejectsEmptyQueryBeforeWalkingFiles(@TempDir @NonNull Path tempDir) {
        ToolExecutionException failure =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                tool.execute(
                                        new GrepSearchTool.Args(
                                                tempDir.toString(), "", null, null)));

        assertEquals("query must not be empty", failure.getMessage());
    }

    @Test
    void namesAbsolutePathWhenTheSuppliedPathDoesNotExist(@TempDir @NonNull Path tempDir) {
        Path missing = tempDir.resolve("missing");

        ToolExecutionException failure =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                tool.execute(
                                        new GrepSearchTool.Args(
                                                missing.toString(), "needle", null, null)));

        assertEquals("Search path does not exist: " + missing, failure.getMessage());
    }
}
