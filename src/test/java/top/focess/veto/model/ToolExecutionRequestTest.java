package top.focess.veto.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolExecutionRequestTest {

  @Test
  void testCreateAndStatusFlow() {
    ToolExecutionRequest req =
        new ToolExecutionRequest("read_safe_file", Map.of("filePath", "test.txt"));

    assertEquals("read_safe_file", req.getCapabilityName());
    assertEquals(ToolExecutionRequest.ToolExecutionStatus.PENDING, req.getStatus());

    req.markRunning();
    assertEquals(ToolExecutionRequest.ToolExecutionStatus.RUNNING, req.getStatus());

    req.markCompleted("result data");
    assertEquals(ToolExecutionRequest.ToolExecutionStatus.COMPLETED, req.getStatus());
    assertEquals("result data", req.getResultPayload());
  }

  @Test
  void testMarkFailed() {
    ToolExecutionRequest req = new ToolExecutionRequest("test", Map.of());
    req.markFailed("error occurred");
    assertEquals(ToolExecutionRequest.ToolExecutionStatus.FAILED, req.getStatus());
    assertEquals("error occurred", req.getErrorMessage());
  }

  @Test
  void testMarkVetoed() {
    ToolExecutionRequest req = new ToolExecutionRequest("test", Map.of());
    req.markVetoed("vetoed by C7 gateway");
    assertEquals(ToolExecutionRequest.ToolExecutionStatus.VETOED, req.getStatus());
  }

  @Test
  void testRequiredCredentials() {
    ToolExecutionRequest req =
        new ToolExecutionRequest(
            "id1",
            "compile_cpp",
            Map.of("file", "x.cpp"),
            Set.of("ssh-key-1", "api-token"),
            "sess1",
            "wf1");

    assertTrue(req.getRequiredCredentials().contains("ssh-key-1"));
    assertTrue(req.getRequiredCredentials().contains("api-token"));
    assertEquals(2, req.getRequiredCredentials().size());
  }

  @Test
  void testEqualityById() {
    ToolExecutionRequest r1 =
        new ToolExecutionRequest("same-id", "cap1", Map.of(), Set.of(), "", "");
    ToolExecutionRequest r2 =
        new ToolExecutionRequest("same-id", "cap2", Map.of(), Set.of(), "", "");
    assertEquals(r1, r2);
    assertEquals(r1.hashCode(), r2.hashCode());
  }
}
