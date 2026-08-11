package top.focess.veto.controller;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import top.focess.veto.i18n.Msg;
import top.focess.veto.vault.KeysteadVault;

/**
 * Maps {@link KeysteadVault.VaultLockedException} - thrown when a vault operation runs before login
 * (or after the vault re-locked) - to a keyed 423 with a clear "log in first" message, instead of
 * leaking as a bare 500.
 */
@RestControllerAdvice
public class VaultExceptionAdvice {

    @ExceptionHandler(KeysteadVault.VaultLockedException.class)
    public @NonNull ResponseEntity<Map<String, String>> vaultLocked() {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(Map.of("status", "error", "message", Msg.get("error.vault.locked")));
    }
}
