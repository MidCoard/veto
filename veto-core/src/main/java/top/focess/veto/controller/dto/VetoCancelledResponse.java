package top.focess.veto.controller.dto;

import org.jspecify.annotations.NonNull;

public record VetoCancelledResponse(@NonNull String status, int declined) implements RestResponse {}
