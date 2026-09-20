package top.focess.veto.controller.dto;

import org.jspecify.annotations.NonNull;

public record CodedErrorResponse(@NonNull String code, @NonNull String error)
        implements RestResponse {}
