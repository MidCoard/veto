package top.focess.veto.model;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentInstanceRepository extends JpaRepository<AgentEntity, String> {

    List<AgentEntity> findBySessionId(String sessionId);
}
