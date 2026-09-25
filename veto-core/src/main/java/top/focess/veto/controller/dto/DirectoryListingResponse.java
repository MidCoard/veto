package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.jspecify.annotations.*;

/** Payload listing a directory's entries together with its path and parent path. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DirectoryListingResponse(
        String path, String parent, @NonNull List<DirectoryEntryResponse> entries)
        implements RestResponse {}
