package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusMessageResponse(@NonNull String status, @NonNull String message)
        implements RestResponse {}
