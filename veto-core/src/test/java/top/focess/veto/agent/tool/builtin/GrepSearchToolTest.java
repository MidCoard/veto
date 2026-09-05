package top.focess.veto.agent.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;

class GrepSearchToolTest {

    private final @NonNull GrepSearchTool tool = new GrepSearchTool();

    @AfterEach
    void clearContext() {
        ToolCallContextHolder.clear();
    }

    @Test
    void rejectsEmptyQueryBeforeWalkingFiles(@TempDir @NonNull Path tempDir) {
        permit(tempDir, Set.of());

        ToolExecutionException failure =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                CapabilityTestCalls.execute(
                                        tool,
                                        new GrepSearchTool.Args(
                                                tempDir.toString(), "", null, null)));

        assertEquals("query must not be empty", failure.getMessage());
        assertEquals("INVALID_QUERY", failure.errorCode());
    }

    @Test
    void namesAbsolutePathWhenTheSuppliedPathDoesNotExist(@TempDir @NonNull Path tempDir) {
        Path missing = tempDir.resolve("missing");
        permit(missing, Set.of());

        ToolExecutionException failure =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () ->
                                CapabilityTestCalls.execute(
                                        tool,
                                        new GrepSearchTool.Args(
                                                missing.toString(), "needle", null, null)));

        assertEquals("Search path does not exist: " + missing, failure.getMessage());
        assertEquals("PATH_NOT_FOUND", failure.errorCode());
    }

    @Test
    void skipsProtectedDescendants(@TempDir @NonNull Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("visible.txt"), "needle visible");
        Path protectedDirectory = Files.createDirectory(tempDir.resolve("protected"));
        Files.writeString(protectedDirectory.resolve("secret.txt"), "needle secret");
        permit(tempDir, Set.of(protectedDirectory));

        String result =
                CapabilityTestCalls.execute(
                        tool, new GrepSearchTool.Args(tempDir.toString(), "needle", null, null));

        assertTrue(result.contains("visible.txt"));
        assertFalse(result.contains("secret.txt"));
        assertFalse(result.contains("needle secret"));
    }

    @Test
    void doesNotFollowRegularFileSymbolicLinks(
            @TempDir @NonNull Path searchRoot, @TempDir @NonNull Path outsideRoot)
            throws Exception {
        Path outside = Files.writeString(outsideRoot.resolve("outside.txt"), "needle outside");
        Path link = searchRoot.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable: " + e.getMessage());
        }
        permit(searchRoot, Set.of());

        String result =
                CapabilityTestCalls.execute(
                        tool, new GrepSearchTool.Args(searchRoot.toString(), "needle", null, null));

        assertEquals("(no matches)", result);
    }

    private static void permit(
            @NonNull Path requestedPath, @NonNull Set<@NonNull Path> protectedPaths) {
        String supplied = requestedPath.toString();
        Path parent = requestedPath.getParent();
        ToolExecutionPermit.AuthorizedPath authorized =
                new ToolExecutionPermit.AuthorizedPath(
                        "absolutePath",
                        supplied,
                        requestedPath,
                        0,
                        true,
                        ToolExecutionPermit.FileIdentity.capture(requestedPath),
                        ToolExecutionPermit.FileIdentity.capture(parent));
        ToolExecutionPermit executionPermit =
                new ToolExecutionPermit(
                        new ToolCall("grep_search", Map.of("absolutePath", supplied), "test-call"),
                        ToolCapability.WORKSPACE_READ,
                        null,
                        null,
                        Map.of("absolutePath", authorized),
                        List.of(parent == null ? requestedPath : parent),
                        parent,
                        DeployerPolicy.PROTECTED,
                        protectedPaths,
                        null);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent",
                        userId,
                        null,
                        null,
                        sessionId,
                        ToolResultPresentationMode.BASIC,
                        false,
                        executionPermit.withCaller("agent", userId, null, null, sessionId)));
        ReflectionTestUtils.invokeMethod(
                ToolCallContextHolder.class, "setCurrentCallId", executionPermit.callId());
    }
}
