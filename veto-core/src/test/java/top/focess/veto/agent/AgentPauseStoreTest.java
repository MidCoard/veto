package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;

@DataJpaTest
@Import({AgentPauseStore.class, AgentWaitStore.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@SuppressWarnings("initialization.field.uninitialized")
class AgentPauseStoreTest {
    @Autowired private @NonNull AgentInstanceRepository repository;
    @Autowired private @NonNull AgentPauseStore store;
    @Autowired private @NonNull AgentWaitStore waits;

    @Test
    void staleLifecycleSaveCannotOverwriteCommittedControls() {
        UUID session = UUID.randomUUID();
        String agent = UUID.randomUUID().toString();
        repository.saveAndFlush(AgentEntity.spawned(agent, session.toString(), "Worker"));
        try {
            AgentEntity stale = repository.findById(agent).orElseThrow();
            store.save(session, agent, true);
            var waiting =
                    new AgentWaitStore.Wait(AgentWaitStore.Reason.APPROVAL, "pending-request");
            waits.save(session, agent, waiting);
            stale.setUserInteractionEnabled(false);
            repository.saveAndFlush(stale);
            assertTrue(store.load(session, agent));
            assertEquals(waiting, waits.load(session, agent).orElseThrow());
            assertFalse(repository.findById(agent).orElseThrow().isUserInteractionEnabled());
            store.save(session, agent, false);
            assertFalse(store.load(session, agent));
            assertEquals(waiting, waits.load(session, agent).orElseThrow());
        } finally {
            repository.deleteById(agent);
        }
    }

    @Test
    void executionWaitCommitsIndependentlyOfPauseAndSession() {
        UUID session = UUID.randomUUID();
        String agent = UUID.randomUUID().toString();
        repository.saveAndFlush(AgentEntity.spawned(agent, session.toString(), "Worker"));
        try {
            store.save(session, agent, true);
            var waiting = new AgentWaitStore.Wait(AgentWaitStore.Reason.APPROVAL, "request-1");
            waits.save(session, agent, waiting);
            assertEquals(waiting, waits.load(session, agent).orElseThrow());
            assertThrows(
                    IllegalStateException.class, () -> waits.save(UUID.randomUUID(), agent, null));
            assertEquals(waiting, waits.load(session, agent).orElseThrow());
            waits.save(session, agent, null);
            assertTrue(waits.load(session, agent).isEmpty());
            assertTrue(store.load(session, agent));
        } finally {
            repository.deleteById(agent);
        }
    }

    @Test
    void pauseCommitsAcrossReadsAndCannotModifyAnotherSession() {
        UUID session = UUID.randomUUID();
        String agent = UUID.randomUUID().toString();
        repository.saveAndFlush(AgentEntity.spawned(agent, session.toString(), "Worker"));
        try {
            assertFalse(store.load(session, agent));
            store.save(session, agent, true);
            assertTrue(store.load(session, agent));
            UUID other = UUID.randomUUID();
            assertFalse(store.load(other, agent));
            assertThrows(IllegalStateException.class, () -> store.save(other, agent, false));
            assertTrue(store.load(session, agent));
            store.save(session, agent, false);
            assertFalse(store.load(session, agent));
        } finally {
            repository.deleteById(agent);
        }
    }
}
