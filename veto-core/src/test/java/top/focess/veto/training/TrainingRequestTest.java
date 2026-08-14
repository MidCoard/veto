package top.focess.veto.training;

import static org.junit.jupiter.api.Assertions.*;

import org.jspecify.annotations.NonNull;
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
        assertEquals(1, requireInt(request.epochs(), "epochs"));
        assertEquals(2e-4, requireDouble(request.learningRate(), "learningRate"));
        assertEquals(2, requireInt(request.batchSize(), "batchSize"));
        assertEquals(8, requireInt(request.loraRank(), "loraRank"));
        assertEquals("/data/custom.jsonl", request.dataPath());
        assertTrue(requireBoolean(request.skipQualityFilter(), "skipQualityFilter"));
    }

    private static int requireInt(Integer value, @NonNull String fieldName) {
        if (value != null) {
            return value.intValue();
        }
        throw new AssertionError(fieldName + " should be present");
    }

    private static double requireDouble(Double value, @NonNull String fieldName) {
        if (value != null) {
            return value.doubleValue();
        }
        throw new AssertionError(fieldName + " should be present");
    }

    private static boolean requireBoolean(Boolean value, @NonNull String fieldName) {
        if (value != null) {
            return value.booleanValue();
        }
        throw new AssertionError(fieldName + " should be present");
    }
}
