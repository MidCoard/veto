package top.focess.veto.builtin;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.capability.ResponseCapability;
import top.focess.veto.api.agent.response.ResponseRequest;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.workflow.*;
import top.focess.veto.api.llm.*;
import top.focess.veto.builtin.planning.SubmitPlanTool;

/** Runs the real plan tool and interpreter on the API-only plugin classpath. */
class PlanningPluginTest {
    @Test
    void submittedPlanRunsUsingOnlyHostCallbacks() throws Exception {
        var submitted = new AtomicReference<ResponseRequest.Plan>();
        var capability =
                new ResponseCapability() {
                    @Override
                    public void submitPlan(ResponseRequest.@NonNull Plan plan) {
                        submitted.set(plan);
                    }

                    @Override
                    public void answerWithCitations(ResponseRequest.@NonNull Answer answer) {
                        fail("unexpected answer submission");
                    }
                };
        var mapper = new ObjectMapper();
        var tool = new SubmitPlanTool();
        var example = ToolDocs.examplesOf(ToolDocs.nonNullClass(SubmitPlanTool.class)).getFirst();
        var args = mapper.readValue(example, ToolDocs.nonNullClass(SubmitPlanTool.Args.class));
        assertEquals("{\"status\":\"accepted\"}", tool.execute(args, capability));
        var plan = submitted.get();
        assertNotNull(plan);
        var execution = plan.execution();
        execution.configure(10);
        execution.install(plan.program(), "plan-call");
        var delivered = new AtomicReference<String>();
        var boundaries = new AtomicInteger();
        var runtime =
                new PlanExecution.Runtime() {
                    @Override
                    public void beforeStep() {
                        boundaries.incrementAndGet();
                    }

                    @Override
                    public boolean running() {
                        return true;
                    }

                    @Override
                    public @NonNull ToolResult tool(
                            @NonNull ToolCall call, @NonNull PlanStepContext context) {
                        throw new AssertionError("unexpected tool");
                    }

                    @Override
                    public PlanExecution.@NonNull Generated generate(
                            @NonNull GenerateAction action, @NonNull ResponseContract contract) {
                        assertEquals("Write a brief welcome message", action.prompt());
                        return new PlanExecution.Generated(
                                new VetoResponse(null, null, "Welcome", null),
                                null,
                                "generation-call");
                    }

                    @Override
                    public void message(
                            @NonNull String text,
                            PlanExecution.@Nullable Source source,
                            @Nullable String modelCallId,
                            boolean forwarded) {
                        delivered.set(text);
                        assertEquals("generation-call", modelCallId);
                    }

                    @Override
                    public void escaped(@NonNull String reason) {
                        fail(reason);
                    }

                    @Override
                    public @NonNull String prompt(
                            @NonNull String source, @NonNull Map<String, Object> data) {
                        throw new AssertionError("unexpected prompt");
                    }
                };
        execution.run(runtime);
        assertEquals(2, boundaries.get());
        assertFalse(execution.active());
        assertEquals("Welcome", delivered.get());
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("top.focess.veto.agent.AgentRunner"));
    }
}
