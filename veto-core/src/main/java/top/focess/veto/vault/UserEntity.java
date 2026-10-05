package top.focess.veto.vault;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.NonNull;

/** JPA entity for the {@code users} table — replaces the old {@code users.json} file. */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "storage_identity", nullable = false, length = 36)
    private @NonNull UUID userId = UUID.randomUUID();

    @Column(length = 64, nullable = false, unique = true)
    private @NonNull String username = "";

    /** Canonical identity of this account incarnation; username reuse never reuses it. */
    public @NonNull UUID getUserId() {
        return userId;
    }

    @Column(name = "password_hash", nullable = false)
    private byte @NonNull [] passwordHash = new byte[0];

    @Column(name = "password_salt", nullable = false)
    private byte @NonNull [] passwordSalt = new byte[0];

    @Column(length = 16, nullable = false)
    private @NonNull String role = "";

    @Column(name = "created_at", nullable = false)
    private @NonNull Instant createdAt = Instant.EPOCH;

    /** JPA-required no-arg constructor. */
    protected UserEntity() {}

    /** Creates a user row with a freshly generated random storage identity. */
    public UserEntity(
            @NonNull String username,
            byte @NonNull [] passwordHash,
            byte @NonNull [] passwordSalt,
            @NonNull String role,
            @NonNull Instant createdAt) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
        this.role = role;
        this.createdAt = createdAt;
    }

    /** Updates authentication material without replacing the account or its storage identity. */
    void updatePassword(byte @NonNull [] passwordHash, byte @NonNull [] passwordSalt) {
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
    }

    public @NonNull String getUsername() {
        return username;
    }

    public byte @NonNull [] getPasswordHash() {
        return passwordHash;
    }

    public byte @NonNull [] getPasswordSalt() {
        return passwordSalt;
    }

    public @NonNull String getRole() {
        return role;
    }

    public @NonNull Instant getCreatedAt() {
        return createdAt;
    }
}
