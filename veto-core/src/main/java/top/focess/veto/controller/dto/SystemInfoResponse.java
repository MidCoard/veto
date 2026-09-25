package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Payload reporting basic operating-system details of the server host. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SystemInfoResponse(
        @NonNull String os,
        @NonNull String arch,
        @NonNull String family,
        @NonNull String pathSeparator,
        @NonNull String pathExample)
        implements RestResponse {}
