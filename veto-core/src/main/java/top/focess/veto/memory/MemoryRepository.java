package top.focess.veto.memory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for {@link MemoryEntity} — the DB-backed memory store. */
@Repository
public interface MemoryRepository extends JpaRepository<MemoryEntity, String> {

    /** Find a user's memories of the given tiers, ordered by recency. */
    @Query(
            "SELECT m FROM MemoryEntity m WHERE m.userId = :userId AND m.tier IN :tiers ORDER BY m.createdAt DESC")
    List<MemoryEntity> findByUserIdAndTierIn(
            @Param("userId") String userId, @Param("tiers") List<String> tiers);

    /** Find a session's LTM rows. */
    List<MemoryEntity> findBySessionId(String sessionId);
}
