package top.focess.veto.agent.continuation;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RequestContinuationRepository
        extends JpaRepository<RequestContinuationEntity, String> {
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @NonNull Optional<RequestContinuationEntity> findById(@NonNull String id);

    void deleteByIdStartingWith(String prefix);
}
