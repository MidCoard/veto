package top.focess.veto.monitor;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestContinuationRepository
        extends JpaRepository<RequestContinuationEntity, String> {
    void deleteByIdStartingWith(String prefix);
}
