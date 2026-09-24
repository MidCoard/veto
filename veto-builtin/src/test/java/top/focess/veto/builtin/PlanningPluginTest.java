package top.focess.veto.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.control.ControlHost;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.workflow.*;
import top.focess.veto.api.llm.*;
import top.focess.veto.builtin.planning.ActionsProgramParser;
import top.focess.veto.builtin.planning.PlanProgram;
import top.focess.veto.builtin.planning.SubmitPlanTool;

/** Runs the real plan tool and interpreter on the API-only plugin classpath. */
class PlanningPluginTest {
    @Test
    void submittedPlanRunsUsingOnlyHostCallbacks() throws Exception {
        var submitted = new AtomicReference<PluginWork>();
        @NonNull ControlHost capability = mock();
        when(capability.tools()).thenReturn(List.of());
        doAnswer(
                        call -> {
                            submitted.set(call.getArgument(0));
                            return null;
                        })
                .when(capability)
                .execute(any());
        var mapper = new ObjectMapper();
        var tool = new SubmitPlanTool();
        var example = ToolDocs.examplesOf(ToolDocs.nonNullClass(SubmitPlanTool.class)).getFirst();
        var args = mapper.readValue(example, ToolDocs.nonNullClass(SubmitPlanTool.Args.class));
        assertEquals("{\"status\":\"accepted\"}", tool.execute(args, capability));
        var plan = submitted.get();
        if (plan == null) throw new AssertionError("Missing plugin work");
        var delivered = new AtomicReference<String>();
        var boundaries = new AtomicInteger();
        var runtime =
                new PluginWork.Runtime() {
                    @Override
                    public void beforeStep() {
                        boundaries.incrementAndGet();
                    }

                    @Override
                    public @NonNull String sourceCallId() {
                        return "plan-call";
                    }

                    @Override
                    public boolean running() {
                        return true;
                    }

                    @Override
                    public @NonNull ToolResult tool(
                            @NonNull ToolCall call, @NonNull ActionContext context) {
                        throw new AssertionError("unexpected tool");
                    }

                    @Override
                    public PluginWork.@NonNull Generated generate(
                            PluginWork.@NonNull ModelInput action,
                            @NonNull ResponseContract contract) {
                        assertEquals("Write a brief welcome message", action.prompt());
                        return new PluginWork.Generated(
                                new VetoResponse(null, null, "Welcome", null),
                                null,
                                "generation-call");
                    }

                    @Override
                    public void message(
                            @NonNull String text,
                            PluginWork.@Nullable Source source,
                            @Nullable String modelCallId,
                            boolean forwarded) {
                        delivered.set(text);
                        assertEquals("generation-call", modelCallId);
                    }

                    @Override
                    public void observation(@NonNull String topic, @NonNull String reason) {
                        fail(reason);
                    }

                    @Override
                    public @NonNull String prompt(
                            @NonNull String source, @NonNull Map<String, Object> data) {
                        throw new AssertionError("unexpected prompt");
                    }
                };
        plan.run(runtime);
        assertEquals(2, boundaries.get());
        var execution = new PlanProgram(mapper);
        execution
                .accepted(ActionsProgramParser.parse(mapper.valueToTree(args.actions())))
                .run(runtime);
        assertFalse(execution.active());
        assertEquals("Welcome", delivered.get());
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("top.focess.veto.agent.AgentRunner"));
    }
}
