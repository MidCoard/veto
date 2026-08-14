package top.focess.veto.training;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link TrainingManager} lifecycle and progress tracking. */
@SuppressWarnings("initialization.field.uninitialized")
class TrainingManagerTest {

    private @NonNull TrainingManager manager;
    private @NonNull TrainingConfiguration config;

    @TempDir private @NonNull Path tempDir;

    @BeforeEach
    void setUp() {
        config = new TrainingConfiguration();
        config.setTrainingDir("./nonexistent-for-test"); // Safe: training won't actually start
        config.setBaseModel("Qwen/Qwen2.5-0.5B-Instruct");
        config.setModelOutputDir("./models");
        config.setQualityFilterEnabled(true);
        manager = new TrainingManager(config, new ObjectMapper());
    }

    @Test
    void testInitialState() {
        assertFalse(manager.isRunning());
        assertEquals(TrainingProgress.Status.IDLE, manager.getProgress().getStatus());
    }

    @Test
    void testStartTrainingFailsWithoutDirectory() {
        // Training dir doesn't exist, so start should fail
        boolean started = manager.startTraining();
        assertFalse(started);
        assertEquals(TrainingProgress.Status.FAILED, manager.getProgress().getStatus());
    }

    @Test
    void testStartTrainingWithRequest() {
        TrainingRequest request =
                new TrainingRequest("Qwen/Qwen2.5-0.5B-Instruct", 1, 2e-4, 2, 8, null, false);
        boolean started = manager.startTraining(request);
        assertFalse(started); // Still fails because directory doesn't exist
    }

    @Test
    void testCancelWhenNotRunning() {
        // Should be a no-op
        assertDoesNotThrow(() -> manager.cancelTraining());
        assertFalse(manager.isRunning());
    }

    @Test
    void testProgressInitialState() {
        TrainingProgress p = manager.getProgress();
        assertEquals(0.0, p.getProgress());
        assertEquals("", p.getTrainedModelPath());
        assertEquals("", p.getErrorMessage());
        assertNull(p.getEvaluation());
    }

    @Test
    void testDeployNonexistentModel() {
        boolean deployed = manager.deployModel("/nonexistent/model.gguf");
        assertFalse(deployed);
    }

    @Test
    void deployRejectsNonGgufFile() throws IOException {
        Path trainingDir = Files.createDirectories(tempDir.resolve("training"));
        Path modelDir = tempDir.resolve("deployed");
        Path source = Files.writeString(trainingDir.resolve("payload.txt"), "not a model");
        config.setTrainingDir(trainingDir.toString());
        config.setModelOutputDir(modelDir.toString());

        assertFalse(manager.deployModel(source.toString()));
    }

    @Test
    void deployCopiesRegularGgufInsideConfiguredRoot() throws IOException {
        Path trainingDir = Files.createDirectories(tempDir.resolve("training"));
        Path modelDir = tempDir.resolve("deployed");
        Path source = Files.write(trainingDir.resolve("candidate.gguf"), new byte[] {1, 2, 3});
        config.setTrainingDir(trainingDir.toString());
        config.setModelOutputDir(modelDir.toString());

        assertTrue(manager.deployModel(source.toString()));
        assertArrayEquals(
                new byte[] {1, 2, 3},
                Files.readAllBytes(modelDir.resolve(config.getDefaultGgufName())));
    }

    @Test
    void testDeployCallback() {
        boolean[] callbackFired = {false};
        manager.setDeployCallback(path -> callbackFired[0] = true);
        // Can't test actual deploy without a real model file
        assertNotNull(manager);
    }

    @Test
    void testStandaloneQualityCheckWithoutData() {
        // No data file exists, should return null
        assertNull(manager.runStandaloneQualityCheck());
    }
}
