package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.tool.*;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.resources.CatalogueTree;

@DefaultQualifier(NonNull.class)
class ToolPresentationExecutionTest {
    @Test
    void forgedCallToHiddenToolNeverReachesHandler(@TempDir @NonNull Path root) {
        var tool = new ConditionalTool();
        var app = mock(ApplicationContext.class);
        when(app.getBeansOfType(AgentTool.class)).thenReturn(Map.of("conditional", tool));
        var engine = new ToolEngineImpl(new ObjectMapper(), List.of(), app);
        engine.init();
        @Nullable ToolDefinition definition = engine.resolveDefinition("conditional");
        if (definition == null) throw new AssertionError("Conditional tool was not registered");
        var call = new ToolCall("conditional", Map.of(), "call");
        var user = UUID.randomUUID();
        var session = UUID.randomUUID();
        var permit =
                ToolExecutionPermit.capture(call, definition, Workspace.single(root, PathMode.REAL))
                        .withCaller("agent", user, "owner", session);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent", user, "owner", session, ToolResultPresentationMode.BASIC, permit));
        try {
            assertEquals(ToolResultStatus.FAILURE, engine.execute(call, definition).status());
            assertFalse(tool.executed);
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @ToolDoc(
            description = "Conditional",
            behavior = "Conditional",
            whenToUse = "When available",
            whenNotToUse = "When hidden",
            resultContract = "ok",
            errorsAndEdgeCases = "Hidden",
            security = "No effects",
            resultFormats = ToolResultFormat.PLAINTEXT,
            examples = "{}",
            returnExamples = "ok")
    static final class ConditionalTool
            implements AgentTool<ConditionalTool.Args>, ToolPresentation {
        boolean executed;

        public @NonNull String getName() {
            return "conditional";
        }

        public @NonNull ToolCapability getCapability() {
            return ToolCapability.PLUGIN_LOCAL;
        }

        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        public @NonNull State describe(@NonNull CatalogueTree tree) {
            return new State(false, Map.of());
        }

        public @NonNull String execute(@NonNull Args args) {
            executed = true;
            return "ok";
        }

        record Args() {}
    }
}
