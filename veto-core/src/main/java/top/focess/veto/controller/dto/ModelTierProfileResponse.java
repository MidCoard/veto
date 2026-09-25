package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.*;

/** Payload summarizing a model tier profile's name, active flag, and creation time. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelTierProfileResponse(
        @NonNull String name, boolean active, @NonNull String createdAt) implements RestResponse {}
