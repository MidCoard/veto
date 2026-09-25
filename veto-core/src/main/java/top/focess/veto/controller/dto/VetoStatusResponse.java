package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Payload reporting the veto gateway's enabled state and cumulative statistics. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VetoStatusResponse(
        @NonNull String status,
        boolean enabled,
        long totalVetoes,
        long totalPasses,
        long totalRedactions,
        @NonNull String timestamp)
        implements RestResponse {}
