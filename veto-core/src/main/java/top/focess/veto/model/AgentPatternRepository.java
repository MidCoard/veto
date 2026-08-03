package top.focess.veto.model;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentPatternRepository extends JpaRepository<AgentPatternEntity, String> {

    @NonNull List<AgentPatternEntity> findByOwner(@NonNull String owner);

    @NonNull Optional<AgentPatternEntity> findByNameAndOwner(
            @NonNull String name, @NonNull String owner);

    void deleteByNameAndOwner(@NonNull String name, @NonNull String owner);

    /** Bulk-delete every pattern owned by {@code owner} (used by user-deletion cascade). */
    void deleteByOwner(@NonNull String owner);
}
