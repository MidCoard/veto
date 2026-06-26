package top.focess.veto.agent.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for {@link PersonaEntity} — the DB-backed persona store. */
@Repository
public interface PersonaRepository extends JpaRepository<PersonaEntity, String> {}
