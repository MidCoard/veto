package top.focess.veto.vault;

import java.util.*;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.model.ToolExecutionRequest;

/**
 * vault Injection Service - injects credentials into sandbox Sandbox temporarily during execution.
 * Credentials are injected only for the duration of the tool execution and are never exposed to bus
 * (Communication Bus).
 *
 * <p>Resolves credentials via {@link KeysteadVault}, which delegates to the current user's open
 * keystead vault handle.
 */
@Component
public class InjectionService {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.vault.InjectionService");

    private final @NonNull KeysteadVault vault;

    // Active injection sessions - cleared after use
    private final @NonNull Map<String, Map<String, String>> activeInjections = new HashMap<>();

    public InjectionService(@NonNull KeysteadVault vault) {
        this.vault = vault;
    }

    /** Inject credentials required by a tool execution request into the sandbox environment. */
    public synchronized @NonNull Map<String, @NonNull String> injectForExecution(
            @NonNull ToolExecutionRequest request) {
        Set<String> requiredCreds = request.getRequiredCredentials();
        if (requiredCreds.isEmpty()) {
            return Map.of();
        }

        Map<String, String> resolved = new HashMap<>();
        List<String> missing = new ArrayList<>();

        for (String credKey : requiredCreds) {
            Optional<String> value = vault.readNoteBody(credKey);
            if (value.isPresent()) {
                resolved.put(credKey, value.get());
            } else {
                missing.add(credKey);
            }
        }

        activeInjections.put(request.getId(), new HashMap<>(resolved));

        if (!missing.isEmpty()) {
            log.warn(
                    "vault Injection: Missing credentials for request {}: {}",
                    request.getId(),
                    missing);
        }

        log.info(
                "vault Injection: Injected {} credentials for request '{}'",
                resolved.size(),
                request.getCapabilityName());

        return Collections.unmodifiableMap(resolved);
    }

    /** Release injected credentials after execution completes. */
    public synchronized void releaseInjection(@NonNull String requestId) {
        Map<String, String> injected = activeInjections.remove(requestId);
        if (injected != null) {
            injected.clear();
            log.debug("vault Injection: Released injection session '{}'", requestId);
        }
    }

    /** Get active injections for a request (keys only, not values). */
    public synchronized @NonNull Set<String> getActiveInjectionKeys(@NonNull String requestId) {
        Map<String, String> injection = activeInjections.get(requestId);
        return injection != null ? injection.keySet() : Set.of();
    }

    public synchronized int getActiveInjectionCount() {
        return activeInjections.size();
    }

    /** Validate that a set of credential keys are available in the vault. */
    public synchronized boolean validateCredentialsAvailable(@NonNull Set<String> credentialKeys) {
        for (String key : credentialKeys) {
            try {
                if (vault.readNoteBody(key).isEmpty()) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }
}
