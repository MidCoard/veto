package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.jspecify.annotations.*;
import top.focess.veto.plugin.api.PluginState;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PluginResponse(
        @NonNull String id,
        @NonNull String version,
        @Nullable String sha256,
        boolean active,
        @NonNull List<String> hooks,
        @NonNull List<String> tools,
        @NonNull PluginState state)
        implements RestResponse {}
