package top.focess.veto.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class SessionRepositoryTest {

    @Autowired SessionRepository repo;

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
