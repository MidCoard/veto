package top.focess.veto.plugin.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

@Converter
public class PluginBindingsConverter
        implements AttributeConverter<
                @org.jspecify.annotations.Nullable List<PluginBinding>,
                @org.jspecify.annotations.Nullable String> {
    private static final ObjectMapper JSON = new ObjectMapper();

    public @org.jspecify.annotations.Nullable String convertToDatabaseColumn(
            @org.jspecify.annotations.Nullable List<PluginBinding> value) {
        if (value == null) return null;
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot save session plugins", e);
        }
    }

    public @org.jspecify.annotations.Nullable List<PluginBinding> convertToEntityAttribute(
            @org.jspecify.annotations.Nullable String value) {
        if (value == null) return null;
        try {
            return JSON.readValue(value, new TypeReference<List<PluginBinding>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read session plugins", e);
        }
    }
}
