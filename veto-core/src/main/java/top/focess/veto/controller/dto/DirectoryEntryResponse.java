package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Payload describing a single entry within a directory listing. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DirectoryEntryResponse(
        @NonNull String name,
        @NonNull String path,
        boolean declared,
        boolean selectable,
        boolean expandable)
        implements RestResponse {}
