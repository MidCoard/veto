package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VaultNoteResponse(@NonNull String title, @NonNull String value)
        implements RestResponse {}
