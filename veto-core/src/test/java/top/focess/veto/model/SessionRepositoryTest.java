package top.focess.veto.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import top.focess.veto.llm.core.ToolResultPresentationMode;

@DataJpaTest
@SuppressWarnings("initialization.field.uninitialized")
class SessionRepositoryTest {

    @Autowired @NonNull SessionRepository repo;
    @Autowired @NonNull TestEntityManager entityManager;

    @Test
    void guidedSelectionSurvivesReloadAndLegacyNullIsDisabled() {
        SessionEntity session =
                new SessionEntity(
                        "alice", "guided", null, 0, ToolResultPresentationMode.BASIC, true);
        repo.saveAndFlush(session);
        entityManager.clear();
        assertTrue(repo.findById(session.getId()).orElseThrow().getGuidedEnabled());
        entityManager
                .getEntityManager()
                .createNativeQuery("UPDATE sessions SET guided_enabled = NULL WHERE id = :id")
                .setParameter("id", session.getId())
                .executeUpdate();
        entityManager.clear();
        assertFalse(repo.findById(session.getId()).orElseThrow().getGuidedEnabled());
    }

    @Test
    void findByOwnerAndName() {
        SessionEntity s = new SessionEntity("alice", "coder");
        s = repo.save(s);

        List<SessionEntity> owned = repo.findByOwner("alice");
        assertEquals(1, owned.size());

        var found = repo.findByNameAndOwner("coder", "alice");
        assertTrue(found.isPresent());
        assertEquals(s.getId(), found.get().getId());
    }
}
