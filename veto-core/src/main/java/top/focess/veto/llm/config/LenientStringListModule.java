package top.focess.veto.llm.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.CollectionLikeType;
import com.fasterxml.jackson.databind.type.CollectionType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Jackson module that leniently deserializes string-element collections on the {@linkplain
 * LlmJacksonConfig#LLM_OBJECT_MAPPER LLM mapper}, so a model that emits a string where the schema
 * asked for a string array does not crash the whole tool call.
 *
 * <p>Tool args are deserialized from the model's JSON. Most providers emit arrays correctly, but
 * Anthropic-compatible clones (e.g. MiniMax) have been observed emitting an inner {@code
 * List<String>} field as a single string - either a JSON-array literal ({@code "[\"a\",\"b\"]"}) or
 * a bracketed list ({@code "[a, b, c]"}, Java {@code List#toString}-style). The default Jackson
 * behavior throws {@code MismatchedInputException}, failing the entire tool call. This module
 * recovers the list from either string form instead of failing.
 *
 * <p>Only string-element collections are affected: arrays of objects (e.g. the {@code calls} list
 * on {@link top.focess.veto.llm.core.VetoResponse}) keep Jackson's default collection deserializer,
 * so response parsing is untouched. Real JSON arrays pass through unchanged.
 */
public final class LenientStringListModule extends SimpleModule {

    public LenientStringListModule() {
        super("LenientStringList");
    }

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.addDeserializers(new StringCollectionDeserializers());
    }

    /**
     * Returns the lenient deserializer only for collections whose element type is {@code String}.
     */
    private static final class StringCollectionDeserializers extends Deserializers.Base {
        @Override
        public @Nullable JsonDeserializer<?> findCollectionDeserializer(
                @NonNull CollectionType collectionType,
                @NonNull DeserializationConfig config,
                @NonNull BeanDescription beanDesc,
                @Nullable TypeDeserializer elementTypeDeserializer,
                @Nullable JsonDeserializer<?> elementDeserializer) {
            return lenientIfStringElement(collectionType);
        }

        @Override
        public @Nullable JsonDeserializer<?> findCollectionLikeDeserializer(
                @NonNull CollectionLikeType collectionType,
                @NonNull DeserializationConfig config,
                @NonNull BeanDescription beanDesc,
                @Nullable TypeDeserializer elementTypeDeserializer,
                @Nullable JsonDeserializer<?> elementDeserializer) {
            return lenientIfStringElement(collectionType);
        }

        private static @Nullable JsonDeserializer<?> lenientIfStringElement(
                @NonNull JavaType collectionType) {
            JavaType elementType = collectionType.getContentType();
            if (elementType != null && elementType.isTypeOrSubTypeOf(String.class)) {
                return LenientStringListDeserializer.INSTANCE;
            }
            return null;
        }
    }

    /**
     * Deserializes a string-element collection from a JSON array (default), a JSON-array literal
     * string, a bracketed list string, or - as a last resort - a bare scalar wrapped as a
     * single-element list. Never throws on a string input.
     */
    private static final class LenientStringListDeserializer
            extends JsonDeserializer<List<String>> {
        static final LenientStringListDeserializer INSTANCE = new LenientStringListDeserializer();

        @Override
        public @NonNull List<String> deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node.isArray()) {
                return toStringList(node);
            }
            if (node.isTextual()) {
                return parseStringAsList(node.asText(), (ObjectMapper) p.getCodec());
            }
            if (node.isNull()) {
                // JSON null -> empty list (safer than null for @NonNull List fields; a @Nullable
                // field
                // getting empty-instead-of-null is harmless for tool args).
                return List.of();
            }
            return List.of(node.toString());
        }

        private static @NonNull List<String> toStringList(@NonNull JsonNode array) {
            List<String> result = new ArrayList<>(array.size());
            for (JsonNode el : array) {
                result.add(el.isTextual() ? el.asText() : el.toString());
            }
            return result;
        }

        /**
         * Recovers a list from a string value: a JSON-array literal is parsed (handles full
         * quoting/escapes); otherwise a bracketed {@code [a, b, c]} form is split on commas and
         * unquoted; a bare scalar becomes a single-element list.
         */
        private static @NonNull List<String> parseStringAsList(
                @NonNull String raw, @NonNull ObjectMapper mapper) {
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                return List.of();
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                // JSON-array literal first - handles "[\"a\",\"b\"]" with any quoting/escapes.
                try {
                    JsonNode parsed = mapper.readTree(trimmed);
                    if (parsed.isArray()) {
                        return toStringList(parsed);
                    }
                } catch (Exception ignore) {
                    // Not valid JSON - fall through to the bracket-split.
                }
                String inner = trimmed.substring(1, trimmed.length() - 1).strip();
                if (inner.isEmpty()) {
                    return List.of();
                }
                List<String> result = new ArrayList<>();
                for (String part : inner.split(",")) {
                    String t = part.strip();
                    if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
                        t = t.substring(1, t.length() - 1);
                    }
                    if (!t.isEmpty()) {
                        result.add(t);
                    }
                }
                return result;
            }
            // A bare scalar the model dropped where a list belonged - keep it as the sole element
            // rather than failing the whole tool call.
            return List.of(raw);
        }
    }
}
