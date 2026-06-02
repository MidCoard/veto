package top.focess.veto.vault;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * JPA entity for the {@code users} table — replaces the old {@code users.json} file.
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private byte[] passwordHash;

    @Column(name = "password_salt", nullable = false)
    private byte[] passwordSalt;

    @Column(length = 16, nullable = false)
    private String role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserEntity() {
    }

    public UserEntity(
            String username,
            byte[] passwordHash,
            byte[] passwordSalt,
            String role,
            Instant createdAt) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
        this.role = role;
        this.createdAt = createdAt;
    }

    public String getUsername() {
        return username;
    }

    public byte[] getPasswordHash() {
        return passwordHash;
    }

    public byte[] getPasswordSalt() {
        return passwordSalt;
    }

    public String getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
