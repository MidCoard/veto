package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.Map;

public record CreateTaskRequest(
        String taskType,
        String id,
        String sourceComponent,
        String targetComponent,
        @JsonSetter(contentNulls = Nulls.SKIP) Map<String, Object> parameters) {}
