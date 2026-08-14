package top.focess.veto.model;

import static org.junit.jupiter.api.Assertions.*;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
@SuppressWarnings("initialization.field.uninitialized")
class AgentInstanceRepositoryTest {

    @Autowired @NonNull AgentInstanceRepository repo;
    @Autowired @NonNull SessionRepository sessions;

    @Test
    void primaryAgentOfSession() {
        SessionEntity s = sessions.save(new SessionEntity("alice", "coder"));
        AgentEntity agent =
                new AgentEntity(
                        s.getId(),
                        "pat-uuid",
                        AgentEntity.Role.PRIMARY,
                        "coder",
                        "DEEPSEEK",
                        "deepseek-v4",
                        "pattern-coder");
        repo.save(agent);
        s.setPrimaryAgentId(agent.getId());
        sessions.save(s);

        var primary =
                repo.findBySessionId(s.getId()).stream()
                        .filter(a -> a.getRole() == AgentEntity.Role.PRIMARY)
                        .findFirst();
        assertTrue(primary.isPresent());
        assertEquals("deepseek-v4", primary.get().getModel());
    }
}
