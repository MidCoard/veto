package top.focess.veto.api.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.agent.tool.ToolJson;
import top.focess.veto.api.agent.tool.ToolPresentation;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResolvedRequest;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.api.plugin.contract.ModelResponsePolicy;
import top.focess.veto.api.plugin.contribution.ContributionSource;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.CommandResult;

class ApiRepairTest {
    public record BrokenResult(@NonNull String value) {
        @Override
        public @NonNull String value() {
            throw new IllegalStateException("synthetic-private-diagnostic");
        }
    }

    @Test
    void diagnosticsDoNotExposeCredentialsOrSerializationFailures() {
        var request =
                new VetoRequest(
                        "system",
                        "user",
                        List.of(),
                        ProviderType.OPENAI,
                        "model",
                        "reference",
                        LlmOptions.defaults(),
                        List.of(),
                        null,
                        false,
                        ResponseContract.ordinary());
        var resolved =
                new ResolvedRequest(request, "https://example.invalid", "synthetic-private-key");
        assertFalse(resolved.toString().contains("synthetic-private-key"));
        assertTrue(resolved.toString().contains("model"));
        var failure =
                assertThrows(
                        ToolExecutionException.class,
                        () -> ToolJson.object(new BrokenResult("value")));
        assertEquals(ToolErrorCode.RESULT.ENCODING_FAILED, failure.errorCode());
        assertEquals("Encoding failed: could not encode the tool JSON result.", failure.content());
    }

    @Test
    void acceptedPluginNamesQualifyContributionsWithoutChangingIdentity() {
        for (String name : List.of("foo_bar", "foo_bar.plugin_name", "foo-bar")) {
            var identity = new PluginIdentity(name, "1.0.0");
            var source =
                    new ContributionSource(
                            identity.id(), identity.version(), ContributionSource.Origin.PLUGIN);
            assertEquals(name + ":tool", source.qualify("tool").value());
        }
    }

    @Test
    void responseAndProcessValuesCaptureOwnedLists() {
        var calls = new ArrayList<@NonNull ToolCall>();
        calls.add(new ToolCall("first", Map.of()));
        var response = new VetoResponse(null, calls, null);
        calls.clear();
        var captured = response.calls();
        if (captured == null) throw new AssertionError("Calls absent");
        assertEquals(1, captured.size());
        assertThrows(UnsupportedOperationException.class, captured::clear);
        assertNull(new VetoResponse(null, null, null).calls());
        var args = new ArrayList<@NonNull String>();
        args.add("approved");
        var command = new Command("binary", args);
        args.clear();
        assertEquals(List.of("approved"), command.args());
        assertThrows(UnsupportedOperationException.class, () -> command.args().clear());
        var exits = new ArrayList<@NonNull Integer>();
        exits.add(0);
        var result = new CommandResult(0, "", "", exits);
        exits.clear();
        assertEquals(List.of(0), result.perCommand());
        assertThrows(UnsupportedOperationException.class, () -> result.perCommand().clear());
    }

    @Test
    void structuredMapsPreserveJsonNullAndSnapshotTheOuterMap() {
        var source = new LinkedHashMap<@NonNull String, @Nullable Object>();
        source.put("optional", null);
        var correction = new ModelResponsePolicy.Correction("prompt.md", source);
        var observation =
                new AgentInbox.Observation(
                        "id", null, "text", Instant.EPOCH, "topic", source, null);
        var presentation = new ToolPresentation.State(true, source);
        source.clear();
        assertTrue(correction.data().containsKey("optional"));
        assertNull(correction.data().get("optional"));
        assertTrue(observation.attributes().containsKey("optional"));
        assertTrue(presentation.facts().containsKey("optional"));
        assertThrows(UnsupportedOperationException.class, () -> correction.data().clear());
        assertThrows(UnsupportedOperationException.class, () -> observation.attributes().clear());
        assertThrows(UnsupportedOperationException.class, () -> presentation.facts().clear());
    }
}
