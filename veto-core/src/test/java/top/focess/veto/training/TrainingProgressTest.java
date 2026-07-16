package top.focess.veto.training;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TrainingProgress} state transitions. */
class TrainingProgressTest {

    private TrainingProgress progress;

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
        assertNotNull(progress.getStartedAt());
        assertNull(progress.getCompletedAt());
    }

    @Test
    void testCompleteTransition() {
        progress.start();
        progress.complete("/path/to/model.gguf");
        assertEquals(TrainingProgress.Status.COMPLETED, progress.getStatus());
        assertEquals(1.0, progress.getProgress());
        assertEquals("/path/to/model.gguf", progress.getTrainedModelPath());
        assertNotNull(progress.getCompletedAt());
    }

    @Test
    void testFailTransition() {
        progress.start();
        progress.fail("Something went wrong");
        assertEquals(TrainingProgress.Status.FAILED, progress.getStatus());
        assertEquals("Something went wrong", progress.getErrorMessage());
        assertNotNull(progress.getCompletedAt());
    }

    @Test
    void testCancelTransition() {
        progress.start();
        progress.cancel();
        assertEquals(TrainingProgress.Status.CANCELLED, progress.getStatus());
        assertNotNull(progress.getCompletedAt());
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

    @Test
    void testEvaluationReport() {
        TrainingProgress.EvaluationReport report =
                new TrainingProgress.EvaluationReport(
                        "/path/to/model.gguf",
                        "/path/to/eval.jsonl",
                        "2026-07-14T00:00:00Z",
                        100,
                        45.2,
                        new TrainingProgress.EvaluationReport.GbnfCompliance(95, 0.95),
                        new TrainingProgress.EvaluationReport.DecisionAccuracy(88, 100, 0.88),
                        new TrainingProgress.EvaluationReport.RedactionAccuracy(
                                80, 5, 15, 0.94, 0.84, 0.89),
                        new TrainingProgress.EvaluationReport.StructuralValidation(90, 100, 0.90));

        progress.setEvaluation(report);
        assertNotNull(progress.getEvaluation());
        assertEquals(0.95, progress.getEvaluation().gbnfCompliance().validJsonRate());
        assertEquals(0.88, progress.getEvaluation().decisionAccuracy().accuracy());
        assertEquals(0.89, progress.getEvaluation().redactionAccuracy().f1());
        assertEquals(0.90, progress.getEvaluation().structuralValidation().accuracy());
    }
}
