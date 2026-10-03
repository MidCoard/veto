package top.focess.veto.agent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.test.util.ReflectionTestUtils;

/** Supplies the construction dependencies required when a VetoAgent uses a mocked runner. */
public final class AgentRunnerFixture {
    private AgentRunnerFixture() {}

    public static @NonNull AgentRunner mockedRunner(@NonNull String id, @NonNull UUID session) {
        AgentRunner runner = mock(AgentRunner.class);
        AgentOutput output = mock(AgentOutput.class);
        ModelSession models = mock(ModelSession.class);
        when(models.pluginContext()).thenReturn(PluginContextSnapshot.from(Set.of(), false));
        var events = new AgentEvents(id, new ObjectMapper(), AgentEventSink.none(), session);
        ReflectionTestUtils.setField(output, "events", events);
        ReflectionTestUtils.setField(runner, "output", output);
        ReflectionTestUtils.setField(runner, "models", models);
        return runner;
    }
}
