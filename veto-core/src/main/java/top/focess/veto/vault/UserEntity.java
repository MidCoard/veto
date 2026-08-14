package top.focess.veto.vault;

import jakarta.persistence.*;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

/** JPA entity for the {@code users} table — replaces the old {@code users.json} file. */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(length = 64)
    private @NonNull String username = "";

    @Column(name = "password_hash", nullable = false)
    private byte @NonNull [] passwordHash = new byte[0];

    @Column(name = "password_salt", nullable = false)
    private byte @NonNull [] passwordSalt = new byte[0];

    @Column(length = 16, nullable = false)
    private @NonNull String role = "";

    @Column(name = "created_at", nullable = false)
    private @NonNull Instant createdAt = Instant.EPOCH;

    protected UserEntity() {}

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
