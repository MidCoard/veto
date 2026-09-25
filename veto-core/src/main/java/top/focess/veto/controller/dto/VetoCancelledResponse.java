package top.focess.veto.controller.dto;

import org.jspecify.annotations.NonNull;

/** Payload reporting how many pending vetoes were declined on cancellation. */
public record VetoCancelledResponse(@NonNull String status, int declined) implements RestResponse {}
