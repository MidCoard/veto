package top.focess.veto.model;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    List<SessionEntity> findByOwner(String owner);

    Optional<SessionEntity> findByNameAndOwner(String name, String owner);
}
