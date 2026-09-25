package top.focess.veto.controller.dto;

import org.jspecify.annotations.NonNull;

/** Error payload carrying a machine-readable code alongside the message. */
public record CodedErrorResponse(@NonNull String code, @NonNull String error)
        implements RestResponse {}
