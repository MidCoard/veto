package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.jspecify.annotations.*;

/** Payload listing the current user's local task summaries and total count. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskListResponse(
        @NonNull String status,
        int total,
        @NonNull List<TaskSummaryResponse> tasks,
        @NonNull String timestamp)
        implements RestResponse {}
