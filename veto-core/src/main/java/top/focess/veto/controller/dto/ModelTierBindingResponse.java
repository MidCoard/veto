package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.NonNull;

/** Payload describing one tier's model provider binding within a model tier profile. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelTierBindingResponse(
        @NonNull String tier,
        String provider,
        String baseUrl,
        String model,
        String credKey,
        Double temp,
        Integer max,
        Integer contextWindow) {}
