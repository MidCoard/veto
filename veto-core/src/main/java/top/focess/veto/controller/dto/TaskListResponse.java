package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.jspecify.annotations.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskListResponse(
        @NonNull String status,
        int total,
        @NonNull List<TaskSummaryResponse> tasks,
        boolean busConnected,
        @NonNull String timestamp)
        implements RestResponse {}
