package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;

class GroupFrontendActionTest {
    @Test
    void publishesBoundedCurrentDataAndPreservesHistoryAfterRuntimeRemoval() throws Exception {
        try (var fixture = new GroupTestHost()) {
            var group = fixture.create();
            var registry = fixture.runtime.registry();
            var history = spy(fixture.runtime.history());
            registry.attachHistory(history);
            clearInvocations(fixture.host);

            registry.put(group.withState(GroupState.COMPLETED, Instant.now()));
            assertTrue(registry.remove(group.groupId()));
            var actions = ArgumentCaptor.forClass(JsonValue.ObjectValue.class);
            verify(fixture.host, times(2))
                    .publish(eq(fixture.grant.scope()), eq("group_changed"), actions.capture());
            verify(history, never()).load(anyString(), any());
            var mapper = new ObjectMapper();
            var first = mapper.valueToTree(JsonValues.toMap(actions.getAllValues().getFirst()));
            var removed = mapper.valueToTree(JsonValues.toMap(actions.getValue()));
            assertEquals(group.groupId().toString(), first.path("group").path("id").asText());
            assertEquals("COMPLETED", first.path("group").path("state").asText());
            assertTrue(first.path("group").path("live").asBoolean());
            assertFalse(removed.path("group").path("live").asBoolean());
            assertFalse(first.path("group").has("brief"));
            assertFalse(first.path("group").has("nodes"));
            assertFalse(first.path("group").has("changes"));
            assertTrue(removed.path("revision").asLong() > first.path("revision").asLong());

            var frontend = new GroupFrontend(fixture.runtime);
            var snapshot =
                    (JsonValue.ObjectValue)
                            frontend.handle(
                                    fixture.caller.scope(),
                                    "list",
                                    new JsonValue.ObjectValue(Map.of()));
            var data = mapper.valueToTree(JsonValues.toMap(snapshot));
            assertEquals(removed.path("revision"), data.path("revision"));
            assertEquals(1, data.path("total").asInt());
            assertEquals("COMPLETED", data.path("items").get(0).path("state").asText());
            assertFalse(data.path("items").get(0).path("live").asBoolean());
            assertEquals(2, data.path("items").get(0).path("changeCount").asInt());
        }
    }

    @Test
    void removalPublishesInterruptedStateAndConcurrentMutationsPublishInOrder() throws Exception {
        try (var fixture = new GroupTestHost()) {
            var group = fixture.create();
            var registry = fixture.runtime.registry();
            clearInvocations(fixture.host);
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var pending = new ArrayList<Future<?>>();
                for (int i = 0; i < 10; i++) {
                    int index = i;
                    pending.add(
                            workers.submit(
                                    () ->
                                            registry.put(
                                                    group.withMate("mate-" + index, "analysis"))));
                }
                for (var worker : pending) worker.get();
            }
            assertTrue(registry.remove(group.groupId()));
            var actions = ArgumentCaptor.forClass(JsonValue.ObjectValue.class);
            verify(fixture.host, times(11))
                    .publish(eq(fixture.grant.scope()), eq("group_changed"), actions.capture());
            var mapper = new ObjectMapper();
            long previous = 0;
            for (var action : actions.getAllValues()) {
                var revision =
                        mapper.valueToTree(JsonValues.toMap(action)).path("revision").asLong();
                assertTrue(revision > previous);
                previous = revision;
            }
            var last = mapper.valueToTree(JsonValues.toMap(actions.getValue()));
            assertEquals("INTERRUPTED", last.path("group").path("state").asText());
            assertFalse(last.path("group").path("live").asBoolean());
            assertEquals(registry.revision(), previous);
        }
    }
}
