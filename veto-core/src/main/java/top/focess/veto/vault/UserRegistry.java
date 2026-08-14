package top.focess.veto.vault;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * User registry backed by PostgreSQL via Spring Data JPA. Stores username, Argon2id password hash,
 * per-user salt, and role.
 *
 * <p>Replaces the old {@code users.json} file-based storage.
 */
@Component
@Transactional
public class UserRegistry {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.vault.UserRegistry");

    private static final int ARGON2_MEMORY_KB = 64 * 1024;
    private static final int ARGON2_ITERATIONS = 3;
    private static final int ARGON2_PARALLELISM = 4;
    private static final int HASH_LENGTH = 32;
    private static final int SALT_LENGTH = 16;
    private static final @NonNull Pattern USERNAME_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final @NonNull UserRepository repo;

    public UserRegistry(@NonNull UserRepository repo) {
        this.repo = repo;
    }

    /** Creates a user with the given role. Use Role.ADMIN for the first user. */
    public @NonNull UserEntity create(
            @NonNull String username, @NonNull String password, @NonNull String role) {
        if (!isValidUsername(username)) {
            throw new IllegalArgumentException(
                    "Username must be 1-64 characters and contain only letters, digits, '.', '_', or '-'");
        }
        if (!isValidRole(role)) {
            throw new IllegalArgumentException("Role must be ADMIN or USER");
        }
        if (repo.existsById(username)) {
            throw new IllegalArgumentException("User '" + username + "' already exists");
        }
        byte[] salt = new byte[SALT_LENGTH];
        newSecureRandom().nextBytes(salt);
        byte[] hash = hashPassword(password, salt);
        UserEntity user = new UserEntity(username, hash, salt, role, Instant.now());
        repo.save(user);
        log.info("User '{}' created with role '{}'", username, role);
        return user;
    }

    /** Whether a username is safe as both a database key and a single vault-directory name. */
    public static boolean isValidUsername(@NonNull String username) {
        return USERNAME_PATTERN.matcher(username).matches();
    }

    /** Whether a role is one of the two persisted authorization roles. */
    public static boolean isValidRole(@NonNull String role) {
        return Role.ADMIN.equals(role) || Role.USER.equals(role);
    }

    /** Authenticates by verifying the password against the stored Argon2id hash. */
    @Transactional(readOnly = true)
    public @NonNull Optional<UserEntity> authenticate(
            @NonNull String username, @NonNull String password) {
        Optional<UserEntity> user = repo.findById(username);
        if (user.isEmpty()) {
            hashPassword(password, new byte[SALT_LENGTH]); // constant-time mitigation
            return Optional.empty();
        }
        byte[] computed = hashPassword(password, user.get().getPasswordSalt());
        if (MessageDigest.isEqual(computed, user.get().getPasswordHash())) {
            return user;
        }
        return Optional.empty();
    }

    /** Returns true if any user exists (vault has been set up). */
    @Transactional(readOnly = true)
    public boolean anyUserExists() {
        return repo.count() > 0;
    }

    @Transactional(readOnly = true)
    public @NonNull Optional<UserEntity> findByUsername(@NonNull String username) {
        return repo.findById(username);
    }

    /** Deletes the user row by username (no cascade - see UserAdminService.deleteUser). */
    public void deleteByUsername(@NonNull String username) {
        repo.findById(username).ifPresent(repo::delete);
    }

    /** Number of users with the ADMIN role (for the last-admin guard on /user delete). */
    @Transactional(readOnly = true)
    public long adminCount() {
        return repo.countByRole(Role.ADMIN);
    }

    /** Whether the user exists and has the ADMIN role. */
    @Transactional(readOnly = true)
    public boolean isAdmin(@NonNull String username) {
        return repo.findById(username).map(u -> Role.ADMIN.equals(u.getRole())).orElse(false);
    }

    /** Lists every user (admin only). */
    @Transactional(readOnly = true)
    public @NonNull List<UserEntity> listAll() {
        return repo.findAll();
    }

    /**
     * Resets the password: new salt + Argon2id hash, preserving role and {@code created_at}. The
     * password is also the keystead vault master password, so a reset invalidates the existing
     * vault (the user must re-provision credentials after re-login).
     */
    public void setPassword(@NonNull String username, @NonNull String password) {
        UserEntity user =
                repo.findById(username)
                        .orElseThrow(
                                () -> new IllegalArgumentException("User not found: " + username));
        byte[] salt = new byte[SALT_LENGTH];
        newSecureRandom().nextBytes(salt);
        byte[] hash = hashPassword(password, salt);
        repo.save(new UserEntity(username, hash, salt, user.getRole(), user.getCreatedAt()));
        log.info("Password reset for user '{}'", username);
    }

    // ── Crypto ──────────────────────────────────────────────────────────────

    private byte @NonNull [] hashPassword(@NonNull String password, byte @NonNull [] salt) {
        Argon2Parameters params =
                new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                        .withSalt(salt)
                        .withParallelism(ARGON2_PARALLELISM)
                        .withMemoryAsKB(ARGON2_MEMORY_KB)
                        .withIterations(ARGON2_ITERATIONS)
                        .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] hash = new byte[HASH_LENGTH];
        generator.generateBytes(password.toCharArray(), hash);
        return hash;
    }

    private static @NonNull SecureRandom newSecureRandom() {
        try {
            return SecureRandom.getInstanceStrong();
        } catch (Exception e) {
            return new SecureRandom();
        }
    }

    /** Role constants. */
    public static final class Role {
        public static final @NonNull String ADMIN = "ADMIN";
        public static final @NonNull String USER = "USER";

        private Role() {}
    }
}
