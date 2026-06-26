package top.focess.veto.model;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentPatternRepository extends JpaRepository<AgentPatternEntity, String> {

    List<AgentPatternEntity> findByOwner(String owner);

    java.util.Optional<AgentPatternEntity> findByNameAndOwner(String name, String owner);

    void deleteByNameAndOwner(String name, String owner);
}
