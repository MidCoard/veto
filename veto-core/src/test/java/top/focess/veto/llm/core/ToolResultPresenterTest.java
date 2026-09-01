package top.focess.veto.llm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.ToolResult;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolResultStatus;

class ToolResultPresenterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void contentOnlyIsTheDefaultPresentation() {
        assertThat(new ToolResultPresenter(mapper).present(success())).isEqualTo("{\"answer\":42}");
    }

    @Test
    void contentOnlyPreservesSuccessAndFailureContentExactly() {
        @NonNull ToolResultPresenter presenter = new ToolResultPresenter(mapper);

        assertThat(presenter.present(success(), ToolResultPresentationMode.CONTENT_ONLY))
                .isEqualTo("{\"answer\":42}");
        assertThat(presenter.present(failure(), ToolResultPresentationMode.CONTENT_ONLY))
                .isEqualTo("memory not found; nothing forgotten");
    }

    @Test
    void metadataModeAddsMachineReadableFieldsWithoutChangingNestedContent() throws Exception {
        @NonNull ToolResultPresenter presenter = new ToolResultPresenter(mapper);

        @NonNull JsonNode success =
                Objects.requireNonNull(
                        mapper.readTree(
                                presenter.present(
                                        success(),
                                        ToolResultPresentationMode.CONTENT_WITH_METADATA)));
        assertThat(success.path("status").asText()).isEqualTo("success");
        assertThat(success.path("format").asText()).isEqualTo("json");
        assertThat(success.path("content").asText()).isEqualTo("{\"answer\":42}");
        assertThat(success.get("errorCode").isNull()).isTrue();

        @NonNull JsonNode failure =
                Objects.requireNonNull(
                        mapper.readTree(
                                presenter.present(
                                        failure(),
                                        ToolResultPresentationMode.CONTENT_WITH_METADATA)));
        assertThat(failure.path("status").asText()).isEqualTo("failure");
        assertThat(failure.path("format").asText()).isEqualTo("plaintext");
        assertThat(failure.path("content").asText())
                .isEqualTo("memory not found; nothing forgotten");
        assertThat(failure.path("errorCode").asText()).isEqualTo("MEMORY_NOT_FOUND");
    }

    private static @NonNull ToolResult success() {
        return new ToolResult(
                "lookup",
                "call-success",
                ToolResultStatus.SUCCESS,
                ToolResultFormat.JSON,
                "{\"answer\":42}",
                null);
    }

    private static @NonNull ToolResult failure() {
        return new ToolResult(
                "forget_memory",
                "call-failure",
                ToolResultStatus.FAILURE,
                ToolResultFormat.PLAINTEXT,
                "memory not found; nothing forgotten",
                "MEMORY_NOT_FOUND");
    }
}
