package top.focess.veto.model;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    @NonNull List<SessionEntity> findByOwner(@NonNull String owner);

    /**
     * The owner's most-recently-active session (max lastActiveAt); used to auto-resume on
     * reconnect.
     */
    @NonNull Optional<SessionEntity> findFirstByOwnerOrderByLastActiveAtDesc(@NonNull String owner);

    @NonNull Optional<SessionEntity> findByNameAndOwner(
            @NonNull String name, @NonNull String owner);

    /**
     * Returns the owner's session whose {@code name} and {@code workspaceRoots} both match exactly
     * (case-sensitive, byte-exact CSV string). Used by {@code createSession} to enforce that two
     * sessions with the same name may exist in different workspaces but not in the same one — the
     * SQL {@code =} on a nullable column treats NULL and a concrete value as distinct, so legacy
     * rows with {@code workspace_roots = NULL} do not collide with new rows bound to a concrete
     * workspace.
     *
     * <p>A DB-level unique constraint on {@code (owner, name, workspace_roots)} would be the
     * defense-in-depth complement; it is not added here because JPA's {@code ddl-auto=update} does
     * not introduce new constraints on an existing table, so it would require a hand-written
     * migration. The application-layer check is sufficient under single-writer semantics (JPA
     * within a transaction).
     */
    @NonNull Optional<SessionEntity> findByOwnerAndNameAndWorkspaceRoots(
            @NonNull String owner, @NonNull String name, @NonNull String workspaceRoots);

    /** Bulk-delete every session owned by {@code owner} (used by user-deletion cascade). */
    void deleteByOwner(@NonNull String owner);
}
