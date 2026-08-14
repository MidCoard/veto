package top.focess.veto.controller;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.i18n.Msg;
import top.focess.veto.vault.KeysteadVault;

/**
 * Vault credential-key management for the web client: model-tier bindings reference a credential by
 * its vault note title (the {@code credKey} field), so the settings UI needs to list, create, read,
 * and delete those notes.
 *
 * <p><b>Values are intentionally readable</b> via {@code GET /api/vault/notes/{title}} - an
 * explicit product decision: the deployment is single-user and self-hosted, and the owner wants
 * keys viewable in the settings UI. Exposure is limited to that explicit per-title GET; the list
 * endpoint stays titles-only.
 */
@RestController
@RequestMapping("/api/vault")
public class VaultController {

    private final @NonNull KeysteadVault vault;

    public VaultController(@NonNull KeysteadVault vault) {
        this.vault = vault;
    }

    /** The credential titles only (never values), sorted for a stable dropdown. */
    @GetMapping("/notes")
    public @NonNull List<String> titles() {
        requireUser();
        return vault.listTitles().stream().sorted().toList();
    }

    /**
     * Reads one credential note's value. Intentionally exposed (single-user self-hosted deployment;
     * see the class javadoc) so the settings UI can show a stored key. 404 when no note exists
     * under the title.
     */
    @GetMapping("/notes/{title}")
    public @NonNull Map<String, String> read(@PathVariable @NonNull String title) {
        requireUser();
        String value =
                vault.readNoteBody(title)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                Msg.get("error.vault.credentialNotFound", title)));
        return Map.of("title", title, "value", value);
    }

    /**
     * Create or overwrite a credential note ({@code title} = the credKey, {@code value} = the key).
     */
    @PutMapping("/notes")
    public @NonNull ResponseEntity<Void> put(@RequestBody @NonNull Map<String, String> body) {
        requireUser();
        String title = body.get("title");
        String value = body.get("value");
        if (title == null || title.isBlank() || value == null || value.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.vault.missingFields"));
        }
        vault.saveNote(title.trim(), value);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/notes/{title}")
    public @NonNull ResponseEntity<Void> delete(@PathVariable @NonNull String title) {
        requireUser();
        if (!vault.deleteNote(title)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, Msg.get("error.vault.credentialNotFound", title));
        }
        return ResponseEntity.noContent().build();
    }

    private void requireUser() {
        if (vault.currentUser() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, Msg.get("error.auth.notLoggedIn"));
        }
    }
}
