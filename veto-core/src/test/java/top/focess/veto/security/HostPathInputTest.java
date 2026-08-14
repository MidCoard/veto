package top.focess.veto.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.ToolDocs;

class HostPathInputTest {

    private static final @NonNull Path WORKING_DIRECTORY =
            Path.of(Objects.requireNonNull(System.getProperty("user.dir")))
                    .toAbsolutePath()
                    .normalize();

    @Test
    void absoluteNormalizedAcceptsCanonicalAbsolutePath() {
        assertEquals(
                WORKING_DIRECTORY,
                HostPathInput.absoluteNormalized(WORKING_DIRECTORY.toString(), "path"));
    }

    @Test
    void absoluteNormalizedRejectsRelativePath() {
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () -> HostPathInput.absoluteNormalized("relative/path", "path"));
    }

    @Test
    void absoluteNormalizedRejectsTraversalSegments() {
        String traversal =
                WORKING_DIRECTORY.resolve("allowed").resolve("..").resolve("escape").toString();
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () -> HostPathInput.absoluteNormalized(traversal, "path"));
    }

    @Test
    void normalizedResolvesRelativeModelPath() {
        assertEquals(
                WORKING_DIRECTORY.resolve("models").resolve("model.gguf"),
                HostPathInput.normalized("models/model.gguf", "modelPath"));
    }
}
