package top.focess.veto.vault;

import static top.focess.veto.util.LogValues.safe;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.keystead.memory.SecretBuffer;
import top.focess.keystead.memory.WipeableByteArrayOutputStream;
import top.focess.keystead.model.SecretId;
import top.focess.keystead.model.SecretMetadata;
import top.focess.keystead.model.SecretType;
import top.focess.keystead.service.DefaultVaultService;
import top.focess.keystead.service.FullVaultBackupService;
import top.focess.keystead.service.VaultHandle;
import top.focess.keystead.service.VaultService;

/**
 * Keystead-backed credential vault. Replaces the old {@code CredentialVault} + {@code SecureStore}
 * + {@code VaultKeyManager}: each user has their own keystead vault (an {@code OneFileVaultStore}
 * at {@code {vaultHome}/keystead/{username}/vault.keystead}), opened with their login password.
 * keystead performs the KDF and vault-key wrapping internally, so veto no longer derives or stores
 * a master/vault key.
 *
 * <p>An unlocked {@link VaultHandle} is cached per user for the lifetime of the login. Consumers
 * retrieve the current user's handle via {@link #currentHandle()} (resolved from {@link
 * CurrentUser}, with a single-active-user fallback for the CLI path) and call keystead's
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

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.vault.KeysteadVault");

    private final @NonNull Path vaultBase;
    private final @NonNull UserRegistry users;
    private final @NonNull VaultService vaultService = new DefaultVaultService();
    private final @NonNull ConcurrentHashMap<UUID, VaultHandle> handles = new ConcurrentHashMap<>();

    /** Constructs the vault with per-user keystead files under the configured vault home. */
    public KeysteadVault(
            @NonNull CredentialVaultConfiguration config, @NonNull UserRegistry users) {
        this.vaultBase = Path.of(config.getVaultHome(), "keystead");
        this.users = users;
    }

    // ── lifecycle ──────────────────────────────────────────────────────────

    /**
     * Creates a new vault for the user without opening it (the handle is closed immediately). Used
     * when an admin provisions another user's vault - the vault exists on disk but is not unlocked
     * until that user logs in.
     */
    public void createVault(@NonNull UUID userId, @NonNull String password) {
        String username =
                users.findByUserId(userId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("User not found: " + userId))
                        .getUsername();
        Path directory = userVaultDirectory(username);
        try {
            Files.createDirectories(vaultBase);
            // Never overwrite or compensate a store belonging to an earlier account.
            Files.createDirectory(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not provision a new vault directory", e);
        }
        char[] pw = password.toCharArray();
        try {
            VaultHandle handle = vaultService.createVault(vaultPath(username), pw);
            handle.close();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                if (status == STATUS_ROLLED_BACK) deleteStore(directory, username);
                            }
                        });
            }
            log.info("KeysteadVault: vault created (closed) for user '{}'", username);
        } catch (RuntimeException | Error e) {
            deleteStore(directory, username);
            throw e;
        } finally {
            wipe(pw);
        }
    }

    /** Opens an existing vault and caches its unlocked handle (login). Reuses an open handle. */
    public @NonNull UUID login(@NonNull String username, @NonNull String password) {
        UUID userId = requireUser(username).getUserId();
        VaultHandle existing = handles.get(userId);
        if (existing != null && !existing.isClosed()) {
            return userId;
        }
        char[] pw = password.toCharArray();
        try {
            VaultHandle handle = openRecoveringPasswordChange(username, pw);
            handles.put(userId, handle);
            log.info("KeysteadVault: vault opened for user {}", userId);
            return userId;
        } finally {
            wipe(pw);
        }
    }

    /**
     * Migrates an unlocked vault using keystead's portable backup. Call only inside an account
     * transaction under the account monitor. The previous file remains until an authenticated login
     * establishes the committed password, including recovery after process interruption.
     */
    public void changePassword(@NonNull UUID userId, @NonNull String password) {
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException(
                    "Vault password migration requires an account transaction");
        VaultHandle handle = handles.get(userId);
        if (handle == null || handle.isClosed())
            throw new IllegalArgumentException(
                    "The user must log in before their vault password can be changed.");
        String username = users.findByUserId(userId).orElseThrow().getUsername();
        Path current = vaultPath(username);
        Path previous = passwordPreviousPath(username);
        Path staged = current.resolveSibling("vault.password-next.keystead");
        if (Files.exists(previous) || Files.exists(staged))
            throw new IllegalArgumentException(
                    "Log in again to finish the previous password change before retrying.");
        char[] backupPassword = (UUID.randomUUID().toString() + UUID.randomUUID()).toCharArray();
        char[] newPassword = password.toCharArray();
        byte[] archive = null;
        boolean replaced = false;
        try {
            synchronized (handle) {
                var backups = new FullVaultBackupService();
                try (var output = new WipeableByteArrayOutputStream()) {
                    backups.export(handle, backupPassword, output);
                    archive = output.toByteArray();
                }
                try (var restored =
                        backups.restore(
                                staged,
                                new ByteArrayInputStream(archive),
                                backupPassword,
                                newPassword)) {
                    if (!handle.vaultFingerprint().equals(restored.vaultFingerprint())
                            || !handle.vaultKeyId().equals(restored.vaultKeyId())
                            || !handle.listSecrets().equals(restored.listSecrets()))
                        throw new IllegalStateException(
                                "Restored vault identity or credentials differ");
                }
                // Stop writers before replacing the file whose handle they held.
                logout(userId);
                Files.copy(current, previous);
                try (var persisted = FileChannel.open(previous, StandardOpenOption.WRITE)) {
                    persisted.force(true);
                }
                Files.move(
                        staged,
                        current,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                replaced = true;
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                if (status == STATUS_ROLLED_BACK) {
                                    try {
                                        Files.move(
                                                previous,
                                                current,
                                                StandardCopyOption.ATOMIC_MOVE,
                                                StandardCopyOption.REPLACE_EXISTING);
                                    } catch (IOException e) {
                                        log.error(
                                                "Vault rollback needs login recovery for user {}",
                                                userId,
                                                e);
                                    }
                                }
                            }
                        });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not migrate the vault password", e);
        } finally {
            wipe(backupPassword);
            wipe(newPassword);
            if (archive != null) Arrays.fill(archive, (byte) 0);
            if (!replaced) cleanupPasswordFile(staged);
            cleanupPasswordFile(staged.resolveSibling(staged.getFileName() + ".lock"));
        }
    }

    private @NonNull VaultHandle openRecoveringPasswordChange(
            @NonNull String username, char @NonNull [] password) {
        Path current = vaultPath(username);
        Path previous = passwordPreviousPath(username);
        if (!Files.exists(previous)) {
            var opened = vaultService.openVault(current, password);
            cleanupPasswordFile(current.resolveSibling("vault.password-next.keystead"));
            return opened;
        }
        // Recovery must never revive an old password after the account hash has committed.
        if (users.authenticate(username, new String(password)).isEmpty())
            throw new IllegalArgumentException("Invalid credentials for vault password recovery");
        VaultHandle opened;
        try {
            opened = vaultService.openVault(current, password);
        } catch (RuntimeException failedCurrent) {
            try (var recovered = vaultService.openVault(previous, password)) {
                if (recovered.isClosed())
                    throw new IllegalStateException("Recovery vault is closed");
            }
            try {
                Files.move(
                        previous,
                        current,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not recover the vault password change", e);
            }
            opened = vaultService.openVault(current, password);
        }
        cleanupPasswordFile(previous);
        cleanupPasswordFile(current.resolveSibling("vault.password-next.keystead"));
        return opened;
    }

    private @NonNull Path passwordPreviousPath(@NonNull String username) {
        return vaultPath(username).resolveSibling("vault.password-previous.keystead");
    }

    private void cleanupPasswordFile(@NonNull Path path) {
        try {
            if (Files.deleteIfExists(path))
                log.debug("Removed completed vault password migration file");
        } catch (IOException e) {
            log.warn("Could not clean vault password migration file: {}", safe(e.getMessage()));
        }
    }

    /** Closes and drops the user's cached handle. The persisted vault is untouched. */
    public void logout(@NonNull UUID userId) {
        VaultHandle handle = handles.remove(userId);
        if (handle != null) {
            handle.close();
            log.info("KeysteadVault: vault closed for user {}", userId);
        }
    }

    /** Closes every open handle (logout-all, used by the unified logout path). */
    public void logoutAll() {
        handles.forEach((u, h) -> h.close());
        handles.clear();
    }

    /**
     * Captures a cleanup operation before the account row is removed. The caller must run it only
     * after successful database commit, while still holding the account monitor.
     */
    public @NonNull Runnable prepareStoreDeletion(@NonNull UUID userId) {
        String username =
                users.findByUserId(userId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("User not found: " + userId))
                        .getUsername();
        Path path = userVaultDirectory(username);
        return () -> {
            logout(userId);
            deleteStore(path, username);
        };
    }

    private void deleteStore(@NonNull Path path, @NonNull String username) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                Files.walkFileTree(
                        path,
                        new SimpleFileVisitor<>() {
                            @Override
                            public @NonNull FileVisitResult visitFile(
                                    @NonNull Path f, @NonNull BasicFileAttributes a)
                                    throws IOException {
                                Files.delete(f);
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public @NonNull FileVisitResult postVisitDirectory(
                                    @NonNull Path d, IOException exc) throws IOException {
                                Files.delete(d);
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } else {
                Files.delete(path);
            }
            log.info("KeysteadVault: deleted vault store for user '{}'", username);
        } catch (IOException e) {
            log.warn(
                    "KeysteadVault: could not fully delete vault store for '{}': {}",
                    username,
                    safe(e.getMessage()));
        }
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
     * The unlocked handle for the current user. Resolves from {@link CurrentUser}; if no context is
     * set (the agent virtual thread, or a single-user CLI), falls back to the sole open handle.
     *
     * @throws VaultLockedException if no handle is available
     */
    public @NonNull VaultHandle currentHandle() {
        UUID user = CurrentUser.id();
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

    /**
     * The request-scoped authenticated user UUID, or {@code null} when the request is anonymous.
     */
    public UUID currentUser() {
        UUID user = CurrentUser.id();
        if (user != null && handles.containsKey(user)) {
            return user;
        }
        return null;
    }

    /**
     * The current request user UUID, or the sole unlocked user for local terminal command handling.
     */
    public UUID currentUserOrOnlyUnlocked() {
        UUID user = currentUser();
        if (user != null) {
            return user;
        }
        if (handles.size() == 1) {
            return handles.keySet().iterator().next();
        }
        return null;
    }

    /**
     * Whether a vault is unlocked for the current request: the {@link CurrentUser} user's handle
     * when a context is set, otherwise any open handle (single-user CLI path).
     */
    public boolean isUnlocked() {
        UUID user = CurrentUser.id();
        if (user != null) {
            return handles.containsKey(user);
        }
        return !handles.isEmpty();
    }

    /** User-specific readiness; never falls back to another logged-in user. */
    public boolean isUnlocked(@NonNull UUID userId) {
        VaultHandle handle = handles.get(userId);
        return handle != null && !handle.isClosed();
    }

    // ── flat key->string helpers (a credential is a SECURE_NOTE titled by its key) ──

    /**
     * Creates a secure note idempotently by exact title, attributes, and body.
     *
     * @param userId authenticated account with an open vault
     * @param title stable note title
     * @param attributes metadata supplied by the caller
     * @param value secret note body
     * @return opaque vault handle
     */
    public @NonNull String createSecureNoteIfAbsent(
            @NonNull UUID userId,
            @NonNull String title,
            @NonNull Map<@NonNull String, @NonNull String> attributes,
            @NonNull String value) {
        if (title.isBlank() || title.length() > 160 || attributes.size() > 16)
            throw new IllegalArgumentException("Invalid secure note metadata");
        for (var entry : attributes.entrySet())
            if (entry.getKey().isBlank()
                    || entry.getKey().length() > 80
                    || entry.getValue().length() > 160)
                throw new IllegalArgumentException("Invalid secure note metadata");
        VaultHandle handle = handles.get(userId);
        if (handle == null || handle.isClosed())
            throw new VaultLockedException("Credential owner vault is locked");
        char[] chars = value.toCharArray();
        try {
            synchronized (handle) {
                var existing =
                        handle.listSecrets().stream()
                                .filter(metadata -> title.equals(metadata.profile().title()))
                                .findFirst();
                if (existing.isPresent()) {
                    SecretMetadata metadata = existing.get();
                    if (metadata.secretType() != SecretType.SECURE_NOTE
                            || !attributes.equals(metadata.profile().attributes()))
                        throw new IllegalArgumentException("Secure note binding does not match");
                    boolean[] same = {false};
                    handle.withSecureNote(
                            metadata.secretId(),
                            note -> note.withBody(body -> same[0] = Arrays.equals(chars, body)));
                    if (!same[0])
                        throw new IllegalArgumentException("Secure note binding does not match");
                    return "cred_" + metadata.secretId().value();
                }
                try (SecretBuffer body = SecretBuffer.fromChars(chars)) {
                    SecretId id =
                            handle.saveSecureNote(
                                    draft -> {
                                        draft.title(title);
                                        attributes.forEach(draft::attribute);
                                        draft.body(body);
                                    });
                    return "cred_" + id.value();
                }
            }
        } finally {
            wipe(chars);
        }
    }

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

    /**
     * Trusted operation seam; the caller must independently authorize the fixed service request.
     */
    public void withImportedCredential(
            @NonNull UUID userId,
            @NonNull String credentialRef,
            @NonNull String service,
            @NonNull Consumer<char @NonNull []> operation) {
        if (!service.matches("[a-z][a-z0-9._-]{0,63}")
                || !credentialRef.matches(
                        "cred_[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("Invalid credential binding");
        VaultHandle handle = handles.get(userId);
        if (handle == null || handle.isClosed())
            throw new VaultLockedException("Credential owner vault is locked");
        synchronized (handle) {
            SecretMetadata metadata =
                    handle.listSecrets().stream()
                            .filter(item -> credentialRef.equals("cred_" + item.secretId().value()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Credential is unavailable"));
            var attributes = metadata.profile().attributes();
            String importId = attributes.get("veto.import.id");
            if (metadata.secretType() != SecretType.SECURE_NOTE
                    || !service.equals(attributes.get("veto.import.service"))
                    || importId == null
                    || !importId.matches("s_[a-f0-9]{32}")
                    || !metadata.profile().title().equals("veto.import." + importId))
                throw new IllegalArgumentException("Credential service binding does not match");
            handle.withSecureNote(metadata.secretId(), note -> note.withBody(operation));
        }
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

    /**
     * Lists all credential titles (keys). The current handle is borrowed and owned by the vault.
     */
    @SuppressWarnings("resource")
    public @NonNull Set<String> listTitles() {
        VaultHandle handle = currentHandle();
        synchronized (handle) {
            return handle.listSecrets().stream()
                    .filter(m -> m.secretType() == SecretType.SECURE_NOTE)
                    .map(m -> m.profile().title())
                    .collect(Collectors.toSet());
        }
    }

    /**
     * Whether a credential with the given title (key) exists in the named user's vault. Unlike the
     * other note helpers, which resolve the current user via {@link CurrentUser}, this takes the
     * UUID explicitly so callers that already hold the user identity (e.g. the model-tier service
     * validating a {@code credKey}) can check without relying on a thread-local context.
     *
     * @throws VaultLockedException if the user's vault is not unlocked
     */
    public boolean hasNote(@NonNull UUID userId, @NonNull String title) {
        VaultHandle handle = handles.get(userId);
        if (handle == null || handle.isClosed()) {
            throw new VaultLockedException("Vault is locked for user: " + userId);
        }
        synchronized (handle) {
            return findNoteByTitle(handle, title) != null;
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private SecretId findNoteByTitle(@NonNull VaultHandle handle, @NonNull String title) {
        return handle.listSecrets().stream()
                .filter(
                        m ->
                                m.secretType() == SecretType.SECURE_NOTE
                                        && title.equals(m.profile().title()))
                .map(SecretMetadata::secretId)
                .findFirst()
                .orElse(null);
    }

    private @NonNull UserEntity requireUser(@NonNull String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
    }

    private @NonNull Path vaultPath(@NonNull String username) {
        return userVaultDirectory(username).resolve("vault.keystead");
    }

    private @NonNull Path userVaultDirectory(@NonNull String username) {
        if (!UserRegistry.isValidUsername(username)) {
            throw new IllegalArgumentException("Invalid vault username");
        }
        Path normalizedBase = vaultBase.toAbsolutePath().normalize();
        Path directory = normalizedBase.resolve(username).normalize();
        if (!normalizedBase.equals(directory.getParent())) {
            throw new IllegalArgumentException("Invalid vault username");
        }
        return directory;
    }

    private static void wipe(char @NonNull [] chars) {
        Arrays.fill(chars, '\0');
    }

    /** Thrown when an operation is attempted on a locked vault. */
    public static class VaultLockedException extends RuntimeException {
        /** Constructs the exception with the given detail message. */
        public VaultLockedException(@NonNull String message) {
            super(message);
        }
    }
}
