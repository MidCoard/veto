package top.focess.veto.model;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface AgentPatternRepository extends JpaRepository<AgentPatternEntity, String> {

    @NonNull List<AgentPatternEntity> findByOwner(@NonNull String owner);

    @NonNull Optional<AgentPatternEntity> findByNameAndOwner(
            @NonNull String name, @NonNull String owner);

    /**
     * Duplicate-tolerant existence check (unlike {@link #findByNameAndOwner}, which throws when
     * legacy duplicate rows exist).
     */
    boolean existsByNameAndOwner(@NonNull String name, @NonNull String owner);

    /**
     * Bulk JPQL delete (single SQL statement, no select-then-remove). The derived-delete form loads
     * each entity and removes it under optimistic locking, so two concurrent deletes of the same
     * name race into ObjectOptimisticLockingFailureException (500); a bulk delete simply affects 0
     * rows on the loser. Runs in its own transaction - without {@code @Transactional} Spring Data
     * executes it outside any EntityManager transaction.
     */
    @Modifying
    @Transactional
    @Query("delete from AgentPatternEntity p where p.name = :name and p.owner = :owner")
    void deleteByNameAndOwner(
            @Param("name") @NonNull String name, @Param("owner") @NonNull String owner);

    /** Bulk-delete every pattern owned by {@code owner} (used by user-deletion cascade). */
    @Transactional
    void deleteByOwner(@NonNull String owner);
}
