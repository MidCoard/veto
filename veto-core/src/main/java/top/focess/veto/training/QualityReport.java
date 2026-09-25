package top.focess.veto.training;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.*;

/** Wire contract emitted by quality_filter.py. Task names are dictionary keys, not DTO fields. */
public record QualityReport(
        @JsonProperty("input_file") @NonNull String inputFile,
        @JsonProperty("total_records") int totalRecords,
        @JsonProperty("valid_records") int validRecords,
        @JsonProperty("invalid_records") int invalidRecords,
        @JsonProperty("duplicates_removed") int duplicatesRemoved,
        @JsonProperty("task_distribution") @NonNull Map<String, Integer> taskDistribution,
        @JsonProperty("invalid_details") @NonNull List<InvalidRecord> invalidDetails,
        @NonNull String status,
        @JsonProperty("output_file") @NonNull String outputFile) {
    public record InvalidRecord(int line, JsonNode id, @NonNull List<String> errors) {}
}
