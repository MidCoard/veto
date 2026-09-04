package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NonNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelTierBindingResponse(
        @NonNull String tier,
        String provider,
        String baseUrl,
        String model,
        String credKey,
        Double temp,
        Integer max) {}
