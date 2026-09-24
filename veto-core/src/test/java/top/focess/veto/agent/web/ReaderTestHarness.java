package top.focess.veto.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.web.ReaderConfig;
import top.focess.veto.builtin.web.WebReader;
import top.focess.veto.integration.plugins.IsolatedExecutions;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.model.tier.ModelTierRegistry;

/** Composes the actual builtin coordinator and generic host without Spring bootstrap. */
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class ReaderTestHarness {
    private ReaderTestHarness() {}

    public static WebReader create(
            ObjectMapper mapper,
            UniformLLMCaller caller,
            ModelTierRegistry models,
            CapabilityTranslator translator,
            SessionAgentRegistry registry,
            TurnLogService history,
            int calls,
            int seconds,
            int input,
            int output,
            Runnable beforeOpen) {
        var executions =
                new IsolatedExecutions(
                        mapper,
                        caller,
                        models,
                        translator,
                        registry,
                        history,
                        new IngressDefense(),
                        128,
                        600,
                        1048576,
                        65536);
        AgentHost host =
                new AgentHost() {
                    public Session session(PluginStorage.SessionScope scope) {
                        throw new UnsupportedOperationException();
                    }

                    public IsolatedAgent isolate(
                            IsolatedAgent.Spec spec, IsolatedAgent.Factory factory) {
                        beforeOpen.run();
                        return executions.open(spec, factory, () -> true);
                    }
                };
        return new WebReader(
                () -> host,
                new ReaderConfig(
                        Map.of(
                                "reader-max-rounds",
                                        new JsonValue.NumberValue(BigDecimal.valueOf(calls)),
                                "reader-timeout-seconds",
                                        new JsonValue.NumberValue(BigDecimal.valueOf(seconds)),
                                "reader-max-input-tokens",
                                        new JsonValue.NumberValue(BigDecimal.valueOf(input)),
                                "reader-max-output-tokens",
                                        new JsonValue.NumberValue(BigDecimal.valueOf(output)))));
    }
}
