package top.focess.veto.training;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link TrainingRequest} record. */
class TrainingRequestTest {

    @Test
    void testDefaults() {
        TrainingRequest request = TrainingRequest.defaults();
        assertNull(request.baseModel());
        assertNull(request.epochs());
        assertNull(request.learningRate());
        assertNull(request.batchSize());
        assertNull(request.loraRank());
        assertNull(request.dataPath());
        assertNull(request.skipQualityFilter());
    }

    @Test
    void testCustomValues() {
        TrainingRequest request =
                new TrainingRequest(
                        "Qwen/Qwen2.5-0.5B-Instruct", 1, 2e-4, 2, 8, "/data/custom.jsonl", true);
        assertEquals("Qwen/Qwen2.5-0.5B-Instruct", request.baseModel());
        assertEquals(1, request.epochs());
        assertEquals(2e-4, request.learningRate());
        assertEquals(2, request.batchSize());
        assertEquals(8, request.loraRank());
        assertEquals("/data/custom.jsonl", request.dataPath());
        assertTrue(request.skipQualityFilter());
    }
}
