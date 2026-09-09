package top.focess.veto.group;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupHistoryRepository extends JpaRepository<GroupHistoryEntity, String> {
    @NonNull List<GroupHistoryEntity> findBySessionIdOrderByRecordedAtAsc(
            @NonNull String sessionId);
}
