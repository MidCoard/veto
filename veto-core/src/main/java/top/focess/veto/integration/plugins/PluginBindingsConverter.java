package top.focess.veto.integration.plugins;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.PluginBinding;

@Converter
@SuppressWarnings(
        "NullableProblems") // WHY: no package @DefaultQualifier, so NullnessChecker needs these
// explicit nullable JPA contracts
public class PluginBindingsConverter
        implements AttributeConverter<@Nullable List<PluginBinding>, @Nullable String> {
    private static final @NonNull ObjectMapper JSON = new ObjectMapper();

    public @Nullable String convertToDatabaseColumn(@Nullable List<PluginBinding> value) {
        if (value == null) return null;
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot save session plugins", e);
        }
    }

    public @Nullable List<PluginBinding> convertToEntityAttribute(@Nullable String value) {
        if (value == null) return null;
        try {
            return JSON.readValue(value, new TypeReference<List<PluginBinding>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read session plugins", e);
        }
    }
}
