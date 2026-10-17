package top.focess.veto.vault;

import org.jspecify.annotations.NonNull;

/** Requested secret operation cannot proceed while the vault is locked. */
public final class VaultLockedException extends RuntimeException {
    /** Constructs the exception with the given detail message. */
    public VaultLockedException(@NonNull String message) {
        super(message);
    }
}
