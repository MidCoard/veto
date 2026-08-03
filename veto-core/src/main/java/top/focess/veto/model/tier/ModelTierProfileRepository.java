package top.focess.veto.model.tier;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA access to {@link ModelTierProfileEntity} (per-user model-tier profiles). */
@Repository
public interface ModelTierProfileRepository extends JpaRepository<ModelTierProfileEntity, String> {

    /** All profiles owned by {@code owner}. */
    @NonNull List<ModelTierProfileEntity> findByOwner(@NonNull String owner);

    /** A profile by name within an owner (names are unique per owner). */
    @NonNull Optional<ModelTierProfileEntity> findByNameAndOwner(
            @NonNull String name, @NonNull String owner);

    /** The owner's currently-active profile, if any (at most one is active per owner). */
    @NonNull Optional<ModelTierProfileEntity> findByOwnerAndActiveTrue(@NonNull String owner);

    void deleteByNameAndOwner(@NonNull String name, @NonNull String owner);

    /** Bulk-delete every profile owned by {@code owner} (used by user-deletion cascade). */
    void deleteByOwner(@NonNull String owner);
}
