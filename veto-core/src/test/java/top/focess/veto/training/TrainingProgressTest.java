package top.focess.veto.training;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TrainingProgress} state transitions. */
@SuppressWarnings("initialization.field.uninitialized")
class TrainingProgressTest {

    private @NonNull TrainingProgress progress;

    @BeforeEach
    void setUp() {
        progress = new TrainingProgress();
    }

    @Test
    void testInitialState() {
        assertEquals(TrainingProgress.Status.IDLE, progress.getStatus());
        assertEquals(0.0, progress.getProgress());
        assertEquals("", progress.getCurrentPhase());
        assertNull(progress.getStartedAt());
        assertNull(progress.getCompletedAt());
        assertNull(progress.getEvaluation());
    }

    @Test
    void testStartTransition() {
        progress.start();
        assertEquals(TrainingProgress.Status.PREPARING_DATA, progress.getStatus());
        requireInstant(progress.getStartedAt(), "start time should be present");
        assertNull(progress.getCompletedAt());
    }

    @Test
    void testCompleteTransition() {
        progress.start();
        progress.complete("/path/to/model.gguf");
        assertEquals(TrainingProgress.Status.COMPLETED, progress.getStatus());
        assertEquals(1.0, progress.getProgress());
        assertEquals("/path/to/model.gguf", progress.getTrainedModelPath());
        requireInstant(progress.getCompletedAt(), "completion time should be present");
    }

    @Test
    void testFailTransition() {
        progress.start();
        progress.fail("Something went wrong");
        assertEquals(TrainingProgress.Status.FAILED, progress.getStatus());
        assertEquals("Something went wrong", progress.getErrorMessage());
        requireInstant(progress.getCompletedAt(), "failure time should be present");
    }

    @Test
    void testCancelTransition() {
        progress.start();
        progress.cancel();
        assertEquals(TrainingProgress.Status.CANCELLED, progress.getStatus());
        requireInstant(progress.getCompletedAt(), "cancellation time should be present");
    }

    @Test
    void testPhaseUpdateTraining() {
        progress.updatePhase("training", 0.5, "Training at step 50");
        assertEquals(TrainingProgress.Status.TRAINING, progress.getStatus());
        assertEquals(0.5, progress.getProgress());
        assertEquals("training", progress.getCurrentPhase());
    }

    @Test
    void testPhaseUpdateConverting() {
        progress.updatePhase("converting", 0.8, "Converting to GGUF");
        assertEquals(TrainingProgress.Status.CONVERTING, progress.getStatus());
    }

    @Test
    void testPhaseUpdateEvaluating() {
        progress.updatePhase("evaluating", 0.9, "Evaluating model");
        assertEquals(TrainingProgress.Status.EVALUATING, progress.getStatus());
    }

    private static @NonNull Instant requireInstant(Instant instant, @NonNull String message) {
        if (instant != null) {
            return instant;
        }
        throw new AssertionError(message);
    }
}
