package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.jspecify.annotations.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SystemInfoResponse(
        @NonNull String os,
        @NonNull String arch,
        @NonNull String family,
        @NonNull String pathSeparator,
        @NonNull String pathExample)
        implements RestResponse {}
