package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GroupOrchestratorProductionWiringTest {
    @Test
    void pluginRuntimeSharesGroupsBetweenToolsPersistenceAndInspection() {
        try (var fixture = new GroupTestHost()) {
            var group = fixture.create();
            assertEquals(
                    group.groupId(),
                    GroupTestHost.required(fixture.runtime.operations().snapshot()).groupId());
            assertEquals(group.groupId().toString(), fixture.runtime.snapshot().getFirst().id());
            assertEquals(
                    group.groupId().toString(),
                    fixture.runtime
                            .history()
                            .load(fixture.scope.sessionId(), fixture.runtime.registry())
                            .getFirst()
                            .id());
        }
    }
}
