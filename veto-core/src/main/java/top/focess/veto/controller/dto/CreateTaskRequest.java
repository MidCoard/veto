package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.Map;

/** Request payload specifying type, optional id, components, and parameters for a new task. */
public record CreateTaskRequest(
        String taskType,
        String id,
        String sourceComponent,
        String targetComponent,
        @JsonSetter(contentNulls = Nulls.SKIP) Map<String, Object> parameters) {}
