package top.focess.veto.vault;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
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

    private static final Logger log = LoggerFactory.getLogger(UserRegistry.class);

    private static final int ARGON2_MEMORY_KB = 64 * 1024;
    private static final int ARGON2_ITERATIONS = 3;
    private static final int ARGON2_PARALLELISM = 4;
    private static final int HASH_LENGTH = 32;
    private static final int SALT_LENGTH = 16;

    private final @NonNull UserRepository repo;

    public
    @NonNull
    UserRegistry(@NonNull UserRepository repo) {
        this.repo = repo;
    }

    /** Creates a user with the given role. Use Role.ADMIN for the first user. */
    public @NonNull UserEntity create(
            @NonNull String username, @NonNull String password, @NonNull String role) {
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

    // ── Crypto ──────────────────────────────────────────────────────────────

    private byte[] hashPassword(String password, byte[] salt) {
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

    private static SecureRandom newSecureRandom() {
        try {
            return SecureRandom.getInstanceStrong();
        } catch (Exception e) {
            return new SecureRandom();
        }
    }

    /** Role constants. */
    public static final class Role {
        public static final String ADMIN = "ADMIN";
        public static final String USER = "USER";

        private Role() {}
    }
}
