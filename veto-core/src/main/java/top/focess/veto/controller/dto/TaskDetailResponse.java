package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.jspecify.annotations.*;

/** Payload carrying full details of one task, including parameters and dependencies. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskDetailResponse(
        @NonNull String status,
        @NonNull String id,
        @NonNull String taskType,
        @NonNull String dagStatus,
        @NonNull Map<String, Object> parameters,
        @NonNull Set<String> dependencies,
        String sourceComponent,
        String targetComponent,
        @NonNull String createdAt,
        @NonNull String updatedAt,
        @NonNull String timestamp)
        implements RestResponse {}
