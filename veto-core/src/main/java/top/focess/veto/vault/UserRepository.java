package top.focess.veto.vault;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {

    /** Count of users with the given role (used by the last-admin guard). */
    long countByRole(String role);
}
