package top.focess.veto.model.tier;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA access to {@link ModelTierBindingEntity} (per-tier bindings within a profile).
 */
@Repository
public interface ModelTierBindingRepository extends JpaRepository<ModelTierBindingEntity, String> {

    /** All bindings belonging to a profile. */
    @NonNull List<ModelTierBindingEntity> findByProfileId(@NonNull String profileId);

    /** The binding for one tier within a profile (at most one per tier per profile). */
    @NonNull Optional<ModelTierBindingEntity> findByProfileIdAndTier(
            @NonNull String profileId, @NonNull ModelTier tier);

    void deleteByProfileId(@NonNull String profileId);
}
