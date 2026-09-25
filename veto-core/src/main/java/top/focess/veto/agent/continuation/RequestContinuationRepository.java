package top.focess.veto.agent.continuation;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Persistence for {@link RequestContinuationEntity}; the id lookup takes a write lock. */
public interface RequestContinuationRepository
        extends JpaRepository<RequestContinuationEntity, String> {
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @NonNull Optional<RequestContinuationEntity> findById(@NonNull String id);

    /** Deletes every continuation row whose id starts with {@code prefix}. */
    void deleteByIdStartingWith(String prefix);
}
