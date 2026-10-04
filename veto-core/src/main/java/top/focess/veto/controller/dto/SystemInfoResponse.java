package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Host operating-system details and client workspace-path syntax. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SystemInfoResponse(
        @NonNull String os,
        @NonNull String arch,
        @NonNull String family,
        @NonNull String pathSeparator,
        @NonNull String pathExample)
        implements RestResponse {}
