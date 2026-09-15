package top.focess.veto.llm.core;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Opaque Gemini response parts, attached by the adapter, never accepted from model JSON. */
public record NativeToolState(
        @NonNull String model, @NonNull String batch, @NonNull String partsJson, int position) {
    public @NonNull Map<String, Object> toPayload() {
        return Map.of("model", model, "batch", batch, "partsJson", partsJson, "position", position);
    }

    public static NativeToolState fromPayload(Object value) {
        if (value instanceof Map<?, ?> map
                && map.get("model") instanceof String model
                && map.get("batch") instanceof String batch
                && map.get("partsJson") instanceof String parts)
            return new NativeToolState(
                    model,
                    batch,
                    parts,
                    map.get("position") instanceof Number n ? n.intValue() : 0);
        return null;
    }
}
