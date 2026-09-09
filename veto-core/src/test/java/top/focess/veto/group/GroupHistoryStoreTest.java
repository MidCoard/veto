package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.llm.core.ToolResultPresentationMode;

class GroupHistoryStoreTest {
    @Test
    void savedTransitionsCanBeReadWithoutAnyLiveGroup() {
        @NonNull GroupHistoryRepository repository = mock();
        List<GroupHistoryEntity> rows = new ArrayList<>();
        when(repository.save(any()))
                .thenAnswer(
                        invocation -> {
                            GroupHistoryEntity row = invocation.getArgument(0);
                            if (row == null) throw new AssertionError("Missing row");
                            rows.add(row);
                            return row;
                        });
        UUID session = UUID.randomUUID();
        when(repository.findBySessionIdOrderByRecordedAtAsc(session.toString())).thenReturn(rows);
        var store = new GroupHistoryStore(repository, new ObjectMapper().findAndRegisterModules());
        var registry = new GroupRegistry();
        registry.attachHistory(store);
        var group =
                Group.create(
                        "leader",
                        "user",
                        "read",
                        new Blackboard(),
                        ExecutionDag.linear(UUID.randomUUID(), List.of("page")),
                        "owner",
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        session);
        registry.put(group);
        registry.put(group);
        registry.disband(group.groupId(), Instant.now());
        assertEquals(2, rows.size(), "Unchanged polling must not create duplicate snapshots");
        var restored = store.load(session.toString(), new GroupRegistry()).get(0);
        assertFalse(restored.live());
        assertEquals("DISBANDED", restored.state());
        assertEquals("page", restored.nodes().get(0).id());
        assertEquals(2, restored.changes().size());
    }
}
