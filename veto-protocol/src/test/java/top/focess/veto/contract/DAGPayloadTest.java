package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DAGPayloadTest {

    @Test
    void testBuilderCreatesValidPayload() {
        DAGPayload payload =
                DAGPayload.builder()
                        .id("test-1")
                        .taskType("compile_cpp")
                        .parameter("source", "test.cpp")
                        .parameter("optimization", "-O2")
                        .dependency("dep-1")
                        .sourceComponent("sandbox")
                        .targetComponent("bus")
                        .build();

        assertEquals("test-1", payload.id());
        assertEquals("compile_cpp", payload.taskType());
        assertEquals(2, payload.parameters().size());
        assertEquals("test.cpp", payload.parameters().get("source"));
        assertTrue(payload.dependencies().contains("dep-1"));
        assertEquals(DAGPayload.DAGPayloadStatus.PENDING, payload.status());
    }

    @Test
    void testAutoGenerateId() {
        DAGPayload payload = DAGPayload.builder().taskType("test").build();
        assertNotNull(payload.id());
        assertFalse(payload.id().isEmpty());
    }

    @Test
    void testWithStatus() {
        DAGPayload payload = DAGPayload.builder().taskType("test").build();
        DAGPayload updated = payload.withStatus(DAGPayload.DAGPayloadStatus.RUNNING);
        assertEquals(DAGPayload.DAGPayloadStatus.RUNNING, updated.status());
        assertEquals(payload.id(), updated.id());
    }

    @Test
    void testWithUpdatedParameters() {
        DAGPayload payload =
                DAGPayload.builder().taskType("test").parameter("key1", "value1").build();
        DAGPayload updated = payload.withUpdatedParameters(Map.of("key2", "value2"));
        assertTrue(updated.parameters().containsKey("key2"));
        assertTrue(updated.parameters().containsKey("key1"));
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
        DAGPayload payload =
                DAGPayload.builder().taskType("test").parameter("key", "value").build();
        assertThrows(
                UnsupportedOperationException.class,
                () -> payload.parameters().put("new", "value"));
    }

    @Test
    void testImmutableDependencies() {
        DAGPayload payload = DAGPayload.builder().taskType("test").build();
        assertThrows(UnsupportedOperationException.class, () -> payload.dependencies().add("x"));
    }
}
