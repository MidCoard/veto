package top.focess.veto.llm.core;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Opaque Gemini response parts, attached by the adapter, never accepted from model JSON. */
public record NativeToolState(
        @NonNull String provider,
        int version,
        @NonNull String model,
        @NonNull String batch,
        @NonNull String partsJson,
        int position) {
    public NativeToolState(
            @NonNull String model, @NonNull String batch, @NonNull String partsJson, int position) {
        this("GEMINI", 1, model, batch, partsJson, position);
    }

    public boolean supports(@NonNull String expectedProvider, @NonNull String expectedModel) {
        return version == 1 && provider.equals(expectedProvider) && model.equals(expectedModel);
    }

    public @NonNull Map<String, Object> toPayload() {
        return Map.of(
                "provider",
                provider,
                "version",
                version,
                "model",
                model,
                "batch",
                batch,
                "partsJson",
                partsJson,
                "position",
                position);
    }

    public static NativeToolState fromPayload(Object value) {
        if (value instanceof Map<?, ?> map
                && (!map.containsKey("provider") || map.get("provider") instanceof String)
                && (!map.containsKey("version") || map.get("version") instanceof Integer)
                && map.get("model") instanceof String model
                && map.get("batch") instanceof String batch
                && map.get("partsJson") instanceof String parts)
            return new NativeToolState(
                    map.get("provider") instanceof String provider ? provider : "GEMINI",
                    map.get("version") instanceof Number version ? version.intValue() : 1,
                    model,
                    batch,
                    parts,
                    map.get("position") instanceof Number n ? n.intValue() : 0);
        return null;
    }
}
