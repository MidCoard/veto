package top.focess.veto.model.tier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA access to {@link ModelTierProfileEntity} (per-user model-tier profiles). */
@Repository
public interface ModelTierProfileRepository extends JpaRepository<ModelTierProfileEntity, String> {

    /** All profiles owned by {@code userId}. */
    @NonNull List<ModelTierProfileEntity> findByUserId(@NonNull UUID userId);

    /** A profile by name within an userId (names are unique per user). */
    @NonNull Optional<ModelTierProfileEntity> findByNameAndUserId(
            @NonNull String name, @NonNull UUID userId);

    /** The user's currently-active profile, if any (at most one is active per user). */
    @NonNull Optional<ModelTierProfileEntity> findByUserIdAndActiveTrue(@NonNull UUID userId);

    /** Delete the user's profile with the given name. */
    void deleteByNameAndUserId(@NonNull String name, @NonNull UUID userId);

    /** Bulk-delete every profile owned by {@code userId} (used by user-deletion cascade). */
    void deleteByUserId(@NonNull UUID userId);
}
