package top.focess.veto.builtin.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import top.focess.veto.api.agent.control.ControlHost;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.workflow.PluginWork;
import top.focess.veto.api.plugin.contract.JsonValue;

class PlanConfigTest {
    @Test
    void defaultsAndExplicitConfigurationBelongToBuiltin() {
        assertEquals(1000, PlanConfig.from(new JsonValue.ObjectValue(Map.of())).maxSteps());
        assertEquals(5, configured("5").maxSteps());
        assertEquals(Integer.MAX_VALUE, configured(Integer.toString(Integer.MAX_VALUE)).maxSteps());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PlanConfig.from(
                                new JsonValue.ObjectValue(
                                        Map.of(
                                                "plan-max-steps",
                                                new JsonValue.BooleanValue(true)))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2147483648", "not-a-number"})
    void invalidLimitsFailBeforePlanExecution(@NonNull String value) {
        assertThrows(IllegalArgumentException.class, () -> configured(value));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 1000})
    void submittedLoopEnforcesPluginLimitWithoutHostStepPolicy(int limit) throws Exception {
        var mapper = new ObjectMapper();
        var tool = new SubmitPlanTool(configured(Integer.toString(limit)));
        @NonNull ControlHost capability = mock();
        when(capability.tools()).thenReturn(List.of());
        var args =
                mapper.readValue(
                        """
                {"actions":[
                  {"id":"spin","label":"Repeat","type":"conditional_goto",
                   "check":{"kind":"empty","var":"unset"},"true_goto":0,"false_goto":1},
                  {"id":"stop","label":"Finish","type":"STOP"}]}
                """,
                        ToolDocs.nonNullClass(SubmitPlanTool.Args.class));
        tool.execute(args, capability);
        var submitted = ArgumentCaptor.forClass(ToolDocs.nonNullClass(PluginWork.class));
        verify(capability).execute(submitted.capture());
        var plan = submitted.getValue();
        if (plan == null) throw new AssertionError("Missing accepted plan");
        PluginWork.@NonNull Runtime runtime = mock();
        when(runtime.running()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> plan.run(runtime));
        verify(runtime, times(limit + 1)).beforeStep();
        verify(runtime).observation(eq("plan_escape"), contains("step limit exceeded"));
        var execution = new PlanProgram(mapper, configured(Integer.toString(limit)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        execution
                                .accepted(
                                        ActionsProgramParser.parse(
                                                mapper.valueToTree(args.actions())))
                                .run(runtime));
        assertFalse(execution.active());
    }

    private static @NonNull PlanConfig configured(@NonNull String value) {
        return PlanConfig.from(
                new JsonValue.ObjectValue(
                        Map.of("plan-max-steps", new JsonValue.StringValue(value))));
    }
}
