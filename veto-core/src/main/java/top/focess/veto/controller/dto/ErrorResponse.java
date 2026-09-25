package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Error payload carrying a human-readable message. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(@NonNull String error) implements RestResponse {}
