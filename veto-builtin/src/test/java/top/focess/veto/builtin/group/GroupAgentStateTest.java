package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.api.plugin.contract.JsonValue;

class GroupAgentStateTest {
    @Test
    void reconfigurationRetainsTransitionUntilTheExactAgentTerminates() {
        try (var fixture = new GroupTestHost()) {
            fixture.runtime.configure(fixture.configuration);
            fixture.runtime.transition(fixture.caller, "runtime-leader", "original");
            var updated =
                    new AgentConfiguration.Context(
                            fixture.configuration.owner(),
                            fixture.grant,
                            fixture.agents,
                            "leader",
                            fixture.configuration.base(),
                            fixture.configuration.authorizedTools(),
                            "updated task");
            var intent = GroupTestHost.required(fixture.runtime.configure(updated));
            var transition = GroupTestHost.required(intent.transition());
            assertEquals("runtime-leader", transition.prompt());
            assertEquals(
                    "updated task",
                    transition.data().values().get("task") instanceof JsonValue.StringValue value
                            ? value.value()
                            : null);
            fixture.runtime.onAgentTerminated(
                    new AgentTerminatedEvent(
                            new Scope.AgentScope(
                                    "different-owner", fixture.grant.scope().session(), "leader")));
            var retained =
                    GroupTestHost.required(
                            GroupTestHost.required(fixture.runtime.configure(updated))
                                    .transition());
            assertEquals(transition, retained);
            fixture.runtime.onAgentTerminated(new AgentTerminatedEvent(fixture.caller.scope()));
            assertNull(GroupTestHost.required(fixture.runtime.configure(updated)).transition());
        }
    }
}
