package top.focess.veto.agent.skills;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for {@link SkillEntity} — the DB-backed skill hash store. */
@Repository
public interface SkillRepository extends JpaRepository<SkillEntity, String> {

    /** Lookup by (name, sourceType) — natural key for skills. */
    SkillEntity findByNameAndSourceType(@NonNull String name, @NonNull String sourceType);
}
