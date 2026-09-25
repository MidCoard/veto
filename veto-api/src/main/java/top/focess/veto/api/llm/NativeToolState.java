package top.focess.veto.api.llm;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Opaque provider response blocks, attached by the adapter, never accepted from model JSON.
 *
 * @param provider adapter identifier that owns the state format
 * @param version state payload format version
 * @param model exact model that produced the state
 * @param batch provider-defined response batch identifier
 * @param partsJson provider-defined opaque state JSON
 * @param position position of this block in the provider response
 */
public record NativeToolState(
        @NonNull String provider,
        int version,
        @NonNull String model,
        @NonNull String batch,
        @NonNull String partsJson,
        int position) {
    /**
     * Checks whether this opaque block can be replayed by an adapter and model.
     *
     * @param expectedProvider adapter identifier expected by the caller
     * @param expectedModel exact model expected by the caller
     * @return whether provider, model, and supported version match
     */
    public boolean supports(@NonNull String expectedProvider, @NonNull String expectedModel) {
        return version == 1 && provider.equals(expectedProvider) && model.equals(expectedModel);
    }

    /**
     * Serializes this opaque state for durable conversation history.
     *
     * @return the complete versioned payload written to durable conversation history
     */
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

    /**
     * Reads current and legacy durable payloads. Missing provider/version/position fields retain
     * their historical Gemini v1 defaults; malformed values return {@code null}.
     *
     * @param value durable payload value
     * @return decoded state, or {@code null} when malformed
     */
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
