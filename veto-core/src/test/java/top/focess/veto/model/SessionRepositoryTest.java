package top.focess.veto.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import top.focess.veto.vault.TestUsers;

@DataJpaTest
@SuppressWarnings("initialization.field.uninitialized")
class SessionRepositoryTest {

    @Autowired @NonNull SessionRepository repo;

    @Test
    void findByUserIdAndName() {
        SessionEntity s = new SessionEntity(TestUsers.ALICE, "coder");
        s = repo.save(s);

        List<SessionEntity> owned = repo.findByUserId(TestUsers.ALICE);
        assertEquals(1, owned.size());

        var found = repo.findByNameAndUserId("coder", TestUsers.ALICE);
        assertTrue(found.isPresent());
        assertEquals(s.getId(), found.get().getId());
    }
}
