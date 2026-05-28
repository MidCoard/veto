package top.focess.veto.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;

class DAGPayloadTest {

    @Test
    void testBuilderCreatesValidPayload() {
        DAGPayload payload = DAGPayload.builder()
            .id("test-1")
            .taskType("compile_cpp")
            .parameter("source", "test.cpp")
            .parameter("optimization", "-O2")
            .dependency("dep-1")
            .sourceComponent("C6")
            .targetComponent("C3")
            .build();

        assertEquals("test-1", payload.getId());
        assertEquals("compile_cpp", payload.getTaskType());
        assertEquals(2, payload.getParameters().size());
        assertEquals("test.cpp", payload.getParameters().get("source"));
        assertTrue(payload.getDependencies().contains("dep-1"));
        assertEquals(DAGPayload.DAGPayloadStatus.PENDING, payload.getStatus());
    }

    @Test
    void testAutoGenerateId() {
        DAGPayload payload = DAGPayload.builder()
            .taskType("test")
            .build();
        assertNotNull(payload.getId());
        assertFalse(payload.getId().isEmpty());
    }

    @Test
    void testWithStatus() {
        DAGPayload payload = DAGPayload.builder().taskType("test").build();
        DAGPayload updated = payload.withStatus(DAGPayload.DAGPayloadStatus.RUNNING);
        assertEquals(DAGPayload.DAGPayloadStatus.RUNNING, updated.getStatus());
        assertEquals(payload.getId(), updated.getId());
    }

    @Test
    void testWithUpdatedParameters() {
        DAGPayload payload = DAGPayload.builder()
            .taskType("test")
            .parameter("key1", "value1")
            .build();
        DAGPayload updated = payload.withUpdatedParameters(Map.of("key2", "value2"));
        assertTrue(updated.getParameters().containsKey("key2"));
        assertTrue(updated.getParameters().containsKey("key1"));
    }

    @Test
    void testEqualityById() {
        DAGPayload p1 = DAGPayload.builder().id("same").taskType("a").build();
        DAGPayload p2 = DAGPayload.builder().id("same").taskType("b").build();
        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
    }

    @Test
    void testImmutableParameters() {
        DAGPayload payload = DAGPayload.builder()
            .taskType("test")
            .parameter("key", "value")
            .build();
        assertThrows(UnsupportedOperationException.class, () -> payload.getParameters().put("new", "value"));
    }

    @Test
    void testImmutableDependencies() {
        DAGPayload payload = DAGPayload.builder().taskType("test").build();
        assertThrows(UnsupportedOperationException.class, () -> payload.getDependencies().add("x"));
    }
}
