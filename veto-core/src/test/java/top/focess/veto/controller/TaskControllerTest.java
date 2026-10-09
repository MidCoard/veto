package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.controller.dto.CreateTaskRequest;
import top.focess.veto.controller.dto.TaskCancelledResponse;
import top.focess.veto.controller.dto.TaskCreatedResponse;
import top.focess.veto.controller.dto.TaskDetailResponse;
import top.focess.veto.controller.dto.TaskListResponse;
import top.focess.veto.vault.TestUsers;

class TaskControllerTest {
    @Test
    void localTaskRegistrationPreservesOwnerIsolationAndCancellation() {
        var authorization = mock(RequestAuthorization.class);
        when(authorization.requireUserId()).thenReturn(TestUsers.ALICE);
        var controller = new TaskController(authorization);
        var request = new CreateTaskRequest("compile", "task", null, null, Map.of("file", "main.java"));
        var created = assertInstanceOf(TaskCreatedResponse.class, controller.createTask(request).getBody());
        assertEquals("PENDING", created.dagStatus());
        var duplicate = assertThrows(ResponseStatusException.class, () -> controller.createTask(request));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        var owned = assertInstanceOf(TaskListResponse.class, controller.listTasks().getBody());
        assertEquals(1, owned.total());
        var detail = assertInstanceOf(TaskDetailResponse.class, controller.getTask("task").getBody());
        assertEquals("main.java", detail.parameters().get("file"));

        when(authorization.requireUserId()).thenReturn(TestUsers.BOB);
        var foreign = assertInstanceOf(TaskListResponse.class, controller.listTasks().getBody());
        assertEquals(0, foreign.total());
        assertEquals(HttpStatus.NOT_FOUND, controller.getTask("task").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.cancelTask("task").getStatusCode());

        when(authorization.requireUserId()).thenReturn(TestUsers.ALICE);
        var cancelled = assertInstanceOf(TaskCancelledResponse.class, controller.cancelTask("task").getBody());
        assertEquals("CANCELLED", cancelled.newStatus());
        var updated = assertInstanceOf(TaskDetailResponse.class, controller.getTask("task").getBody());
        assertEquals("CANCELLED", updated.dagStatus());
    }
}
