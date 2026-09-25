package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Payload confirming directory creation and reporting its path. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DirectoryCreatedResponse(@NonNull String path) implements RestResponse {}
