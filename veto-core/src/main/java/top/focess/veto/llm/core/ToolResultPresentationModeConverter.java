package top.focess.veto.llm.core;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/** Keeps the public mode names small while remaining compatible with existing database rows. */
@Converter
public class ToolResultPresentationModeConverter
        implements AttributeConverter<ToolResultPresentationMode, String> {

    private static final @NonNull String LEGACY_BASIC = "CONTENT_ONLY";
    private static final @NonNull String LEGACY_DETAILED = "CONTENT_WITH_METADATA";

    @Override
    public @NonNull String convertToDatabaseColumn(ToolResultPresentationMode attribute) {
        return attribute != null && attribute.detailed() ? LEGACY_DETAILED : LEGACY_BASIC;
    }

    @Override
    public @NonNull ToolResultPresentationMode convertToEntityAttribute(String dbData) {
        if (dbData == null || LEGACY_BASIC.equals(dbData) || "BASIC".equals(dbData)) {
            return Objects.requireNonNull(ToolResultPresentationMode.BASIC);
        }
        if (LEGACY_DETAILED.equals(dbData) || "DETAILED".equals(dbData)) {
            return Objects.requireNonNull(ToolResultPresentationMode.DETAILED);
        }
        throw new IllegalArgumentException("Unknown tool-result presentation mode: " + dbData);
    }
}
