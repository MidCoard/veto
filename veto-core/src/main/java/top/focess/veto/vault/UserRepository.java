package top.focess.veto.vault;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for {@link UserEntity} rows keyed by username. */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {

    /** Count of users with the given role (used by the last-admin guard). */
    long countByRole(@NonNull String role);
}
