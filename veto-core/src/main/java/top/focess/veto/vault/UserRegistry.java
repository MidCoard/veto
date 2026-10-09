package top.focess.veto.vault;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * User registry backed by PostgreSQL via Spring Data JPA. Stores username, Argon2id password hash,
 * per-user salt, and role.
 *
 * <p>This registry owns persisted account facts and password verification, not login tokens,
 * unlocked vault handles, terminal authentication or lifecycle events. Those runtime effects belong
 * to {@link AuthLifecycleManager}; workflows coordinate both through {@link
 * AuthLifecycleManager#locks()}.
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

    /** Constructs the registry persisting through the given repository. */
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
        if (repo.existsByUsername(username)) {
            throw new IllegalArgumentException("User '" + username + "' already exists");
        }
        byte[] salt = new byte[SALT_LENGTH];
        newSecureRandom().nextBytes(salt);
        byte[] hash = hashPassword(password, salt);
        UserEntity user = new UserEntity(username, hash, salt, role, Instant.now());
        try {
            user =
                    Objects.requireNonNull(
                            repo.saveAndFlush(user), "Account insert returned no account");
        } catch (DataIntegrityViolationException duplicate) {
            // Database uniqueness is authoritative when concurrent requests pass the existence
            // check.
            throw new IllegalArgumentException("User '" + username + "' already exists", duplicate);
        }
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
        Optional<UserEntity> user = repo.findByUsername(username);
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

    /** Looks up a user by exact username. */
    @Transactional(readOnly = true)
    public @NonNull Optional<UserEntity> findByUsername(@NonNull String username) {
        return repo.findByUsername(username);
    }

    /** Looks up the account by its canonical identity. */
    @Transactional(readOnly = true)
    public @NonNull Optional<UserEntity> findByUserId(@NonNull UUID userId) {
        return repo.findById(userId);
    }

    /** Deletes one account; lifecycle and persisted-data cleanup are owned by UserAdminService. */
    public void deleteByUserId(@NonNull UUID userId) {
        repo.findById(userId).ifPresent(repo::delete);
    }

    /** Number of users with the ADMIN role (for the last-admin guard on /user delete). */
    @Transactional(readOnly = true)
    public long adminCount() {
        return repo.countByRole(Role.ADMIN);
    }

    /** Whether the user exists and has the ADMIN role. */
    @Transactional(readOnly = true)
    public boolean isAdmin(@NonNull UUID userId) {
        return repo.findById(userId).map(u -> Role.ADMIN.equals(u.getRole())).orElse(false);
    }

    /** Lists every user (admin only). */
    @Transactional(readOnly = true)
    public @NonNull List<UserEntity> listAll() {
        return repo.findAll();
    }

    /**
     * Resets the password: new salt + Argon2id hash, preserving role and {@code created_at}. The
     * password is also the keystead vault master password. A caller must coordinate the vault
     * password change before changing this hash; changing it alone cannot reset the vault.
     */
    public void setPassword(@NonNull UUID userId, @NonNull String password) {
        UserEntity user =
                repo.findById(userId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("User not found: " + userId));
        byte[] salt = new byte[SALT_LENGTH];
        newSecureRandom().nextBytes(salt);
        byte[] hash = hashPassword(password, salt);
        user.updatePassword(hash, salt);
        repo.save(user);
        log.info("Password reset for user {}", userId);
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
