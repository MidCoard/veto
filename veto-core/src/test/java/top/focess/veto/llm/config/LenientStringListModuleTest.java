package top.focess.veto.llm.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validates {@link LenientStringListModule}: a model that emits a string where a string array was
 * expected must not crash the tool call. The module is registered on the LLM mapper exactly as
 * {@link LlmJacksonConfig} wires it.
 */
class LenientStringListModuleTest {

    /** A record shaped like {@code RunCommandTool.CommandInput} - an inner {@code List<String>}. */
    private record CommandInput(String executable, List<String> args) {}

    private record Args(List<CommandInput> commands, String cwd) {}

    private ObjectMapper llmMapper() {
        return new ObjectMapper()
                .registerModule(new LenientStringListModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    void realArrayPassesThroughUnchanged() throws Exception {
        Args args =
                llmMapper()
                        .readValue(
                                "{\"commands\":[{\"executable\":\"gradle\",\"args\":[\"build\",\"test\"]}],\"cwd\":\"/abs\"}",
                                Args.class);
        assertEquals(List.of("build", "test"), args.commands().get(0).args());
    }

    @Test
    void bracketedListStringRecoversToList() throws Exception {
        // The exact failure observed live: MiniMax emitted args as "[/c, if, not, exist]".
        Args args =
                llmMapper()
                        .readValue(
                                "{\"commands\":[{\"executable\":\"cmd\",\"args\":\"[/c, if, not, exist, E:\\\\minecraft_modpack\\\\]\"}],\"cwd\":\"E:\\\\test\"}",
                                Args.class);
        assertEquals(
                List.of("/c", "if", "not", "exist", "E:\\minecraft_modpack\\"),
                args.commands().get(0).args());
    }

    @Test
    void jsonArrayLiteralStringRecoversToList() throws Exception {
        Args args =
                llmMapper()
                        .readValue(
                                "{\"commands\":[{\"executable\":\"gradle\",\"args\":\"[\\\"build\\\",\\\"test\\\"]\"}],\"cwd\":\"/abs\"}",
                                Args.class);
        assertEquals(List.of("build", "test"), args.commands().get(0).args());
    }

    @Test
    void bareScalarBecomesSingleElementList() throws Exception {
        Args args =
                llmMapper()
                        .readValue(
                                "{\"commands\":[{\"executable\":\"git\",\"args\":\"status\"}],\"cwd\":\"/abs\"}",
                                Args.class);
        assertEquals(List.of("status"), args.commands().get(0).args());
    }
}
