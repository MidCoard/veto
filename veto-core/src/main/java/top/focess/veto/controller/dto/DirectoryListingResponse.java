package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.jspecify.annotations.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DirectoryListingResponse(
        String path, String parent, @NonNull List<DirectoryEntryResponse> entries)
        implements RestResponse {}
