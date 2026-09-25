package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Payload reporting the outcome of a sensitivity check on a payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VetoCheckResponse(
        @NonNull String status, boolean safe, @NonNull String decision, int redactionCount)
        implements RestResponse {}
