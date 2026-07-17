package top.focess.veto.vault;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.keystead.memory.SecretBuffer;
import top.focess.keystead.model.SecretId;
import top.focess.keystead.model.SecretMetadata;
import top.focess.keystead.model.SecretType;
import top.focess.keystead.model.VaultId;
import top.focess.keystead.service.CreateVaultRequest;
import top.focess.keystead.service.DefaultVaultService;
import top.focess.keystead.service.VaultHandle;
import top.focess.keystead.service.VaultService;
import top.focess.keystead.store.FileVaultStore;

/**
 * Keystead-backed credential vault. Replaces the old {@code CredentialVault} + {@code SecureStore}
 * + {@code VaultKeyManager}: each user has their own keystead vault (a {@link FileVaultStore} at
 * {@code {vaultHome}/keystead/{username}/}), opened with their login password. keystead performs
 * the KDF and vault-key wrapping internally, so veto no longer derives or stores a master/vault
 * key.
 *
 * <p>An unlocked {@link VaultHandle} is cached per user for the lifetime of the login. Consumers
 * retrieve the current user's handle via {@link #currentHandle()} (resolved from {@link
 * UserContext}, with a single-active-user fallback for the CLI path) and call keystead's
 * typed-secret API directly. Helpers ({@link #saveNote}, {@link #readNoteBody}, {@link
 * #deleteNote}, {@link #listTitles}) cover the flat key->string vocabulary veto uses (a credential
 * is a {@code SECURE_NOTE} titled by its key).
 *
 * <p>Per-handle synchronization guards concurrent access from the agent virtual thread (credential
 * resolution) and the command thread (credential store); keystead's own thread-safety is not
 * documented, so this serializes handle operations.
 */
@Service
public class KeysteadVault {

    private static final Logger log = LoggerFactory.getLogger(KeysteadVault.class);

    private final Path vaultBase;
    private final ConcurrentHashMap<String, VaultHandle> handles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VaultService> services = new ConcurrentHashMap<>();

    public KeysteadVault(@NonNull CredentialVaultConfiguration config) {
        this.vaultBase = Path.of(config.getVaultHome(), "keystead");
    }

    // ── lifecycle ──────────────────────────────────────────────────────────

    /** Creates a new vault for the user and caches its unlocked handle (signup). */
    public void signup(@NonNull String username, @NonNull String password) {
        char[] pw = password.toCharArray();
        try {
            VaultHandle handle =
                    serviceFor(username).createVault(new CreateVaultRequest(vaultId(username)), pw);
            handles.put(username, handle);
            log.info("KeysteadVault: vault created and opened for user '{}'", username);
        } finally {
            wipe(pw);
        }
    }

    /** Opens an existing vault and caches its unlocked handle (login). Reuses an open handle. */
    public void login(@NonNull String username, @NonNull String password) {
        VaultHandle existing = handles.get(username);
        if (existing != null && !existing.isClosed()) {
            return;
        }
        char[] pw = password.toCharArray();
        try {
            VaultHandle handle = serviceFor(username).openVault(vaultId(username), pw);
            handles.put(username, handle);
            log.info("KeysteadVault: vault opened for user '{}'", username);
        } finally {
            wipe(pw);
        }
    }

    /** Closes and drops the user's cached handle. The persisted vault is untouched. */
    public void logout(@NonNull String username) {
        VaultHandle handle = handles.remove(username);
        if (handle != null) {
            handle.close();
            log.info("KeysteadVault: vault closed for user '{}'", username);
        }
    }

    /** Closes every open handle (logout-all, used by the unified logout path). */
    public void logoutAll() {
        handles.forEach((u, h) -> h.close());
        handles.clear();
    }

    @PreDestroy
    void shutdown() {
        handles.forEach(
                (u, h) -> {
                    try {
                        h.close();
                    } catch (RuntimeException e) {
                        log.warn("KeysteadVault: error closing handle for '{}'", u, e);
                    }
                });
        handles.clear();
    }

    // ── current-user resolution ────────────────────────────────────────────

    /**
     * The unlocked handle for the current user. Resolves from {@link UserContext}; if no context is
     * set (the agent virtual thread, or a single-user CLI), falls back to the sole open handle.
     *
     * @throws VaultLockedException if no handle is available
     */
    public @NonNull VaultHandle currentHandle() {
        String user = UserContext.get();
        if (user != null) {
            VaultHandle handle = handles.get(user);
            if (handle != null && !handle.isClosed()) {
                return handle;
            }
            throw new VaultLockedException("Vault is locked for user: " + user);
        }
        if (handles.size() == 1) {
            return handles.values().iterator().next();
        }
        throw new VaultLockedException("Vault is locked - authenticate first");
    }

    /** The currently authenticated username, or {@code null} if none/unlocked. */
    public @Nullable String currentUser() {
        String user = UserContext.get();
        if (user != null && handles.containsKey(user)) {
            return user;
        }
        if (handles.size() == 1) {
            return handles.keySet().iterator().next();
        }
        return null;
    }

    public boolean isUnlocked() {
        String user = UserContext.get();
        if (user != null) {
            return handles.containsKey(user);
        }
        return !handles.isEmpty();
    }

    // ── flat key->string helpers (a credential is a SECURE_NOTE titled by its key) ──

    /**
     * Stores (upserts) a credential: deletes any existing note with the title, then saves a new
     * one.
     */
    public void saveNote(@NonNull String title, @NonNull String value) {
        VaultHandle handle = currentHandle();
        char[] chars = value.toCharArray();
        synchronized (handle) {
            SecretId existing = findNoteByTitle(handle, title);
            if (existing != null) {
                handle.deleteSecret(existing);
            }
            try (SecretBuffer body = SecretBuffer.fromChars(chars)) {
                handle.saveSecureNote(d -> d.title(title).body(body));
            }
        }
        log.debug("KeysteadVault: stored credential '{}'", title);
    }

    /** Retrieves a credential by its title (key). */
    public @NonNull Optional<String> readNoteBody(@NonNull String title) {
        VaultHandle handle = currentHandle();
        synchronized (handle) {
            SecretId id = findNoteByTitle(handle, title);
            if (id == null) {
                return Optional.empty();
            }
            String[] holder = new String[1];
            handle.withSecureNote(
                    id,
                    v ->
                            v.withBody(
                                    chars -> {
                                        if (chars != null) {
                                            holder[0] = new String(chars);
                                        }
                                    }));
            return Optional.ofNullable(holder[0]);
        }
    }

    /** Deletes a credential by title. Returns {@code true} if a note was deleted. */
    public boolean deleteNote(@NonNull String title) {
        VaultHandle handle = currentHandle();
        synchronized (handle) {
            SecretId id = findNoteByTitle(handle, title);
            if (id == null) {
                return false;
            }
            handle.deleteSecret(id);
            return true;
        }
    }

    /** Lists all credential titles (keys). */
    public @NonNull Set<String> listTitles() {
        VaultHandle handle = currentHandle();
        synchronized (handle) {
            return handle.listSecrets().stream()
                    .filter(m -> m.type() == SecretType.SECURE_NOTE)
                    .map(m -> m.profile().title())
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private SecretId findNoteByTitle(@NonNull VaultHandle handle, @NonNull String title) {
        return handle.listSecrets().stream()
                .filter(
                        m ->
                                m.type() == SecretType.SECURE_NOTE
                                        && title.equals(m.profile().title()))
                .map(SecretMetadata::id)
                .findFirst()
                .orElse(null);
    }

    private VaultService serviceFor(@NonNull String username) {
        return services.computeIfAbsent(
                username, u -> new DefaultVaultService(new FileVaultStore(vaultBase.resolve(u))));
    }

    private static VaultId vaultId(@NonNull String username) {
        return new VaultId(
                UUID.nameUUIDFromBytes(
                        ("veto-vault:" + username).getBytes(StandardCharsets.UTF_8)));
    }

    private static void wipe(char[] chars) {
        Arrays.fill(chars, '\0');
    }

    /** Thrown when an operation is attempted on a locked vault. */
    public static class VaultLockedException extends RuntimeException {
        public VaultLockedException(@NonNull String message) {
            super(message);
        }
    }
}
