package top.focess.veto.vault;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for {@link UserEntity} rows keyed by canonical user UUID. */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    @NonNull Optional<UserEntity> findByUsername(@NonNull String username);

    boolean existsByUsername(@NonNull String username);

    /** Count of users with the given role (used by the last-admin guard). */
    long countByRole(@NonNull String role);
}
