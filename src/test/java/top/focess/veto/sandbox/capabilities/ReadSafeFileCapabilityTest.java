package top.focess.veto.sandbox.capabilities;

import top.focess.veto.model.ToolExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReadSafeFileCapabilityTest {

    private ReadSafeFileCapability capability;
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("veto.sandbox.tempDir", tempDir.toString());
        capability = new ReadSafeFileCapability();
    }

    @Test
    void testGetName() {
        assertEquals("read_safe_file", capability.getName());
    }

    @Test
    void testReadExistingFile() throws Exception {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello, Veto!");

        ToolExecutionRequest request = new ToolExecutionRequest(
            "read_safe_file", Map.of("filePath", "test.txt"));

        String result = capability.execute(request);
        assertTrue(result.contains("\"status\":\"ok\""));
        assertTrue(result.contains("Hello, Veto!"));
    }

    @Test
    void testRejectsPathTraversal() {
        ToolExecutionRequest request = new ToolExecutionRequest(
            "read_safe_file", Map.of("filePath", "../../etc/passwd"));

        assertThrows(SecurityException.class, () -> capability.execute(request));
    }

    @Test
    void testRejectsMissingFilePath() {
        ToolExecutionRequest request = new ToolExecutionRequest(
            "read_safe_file", Map.of());

        assertThrows(IllegalArgumentException.class, () -> capability.execute(request));
    }
}
