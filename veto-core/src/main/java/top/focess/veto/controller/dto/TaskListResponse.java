package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.jspecify.annotations.*;

/** Payload listing task summaries with the total count and DAG bus connectivity state. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskListResponse(
        @NonNull String status,
        int total,
        @NonNull List<TaskSummaryResponse> tasks,
        boolean busConnected,
        @NonNull String timestamp)
        implements RestResponse {}
