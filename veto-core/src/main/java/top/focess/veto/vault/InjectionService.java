package top.focess.veto.vault;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import top.focess.veto.model.ToolExecutionRequest;

/**
 * vault Injection Service - injects credentials into sandbox Sandbox temporarily during execution.
 * Credentials are injected only for the duration of the tool execution and are never exposed to bus
 * (Communication Bus).
 *
 * <p>Resolves credentials via {@link CredentialVault}, which delegates to the current user's
 * per-user {@link SecureStore}.
 */
@Component
public class InjectionService {

    private static final Logger log = LoggerFactory.getLogger(InjectionService.class);

    private final CredentialVault vault;

    // Active injection sessions - cleared after use
    private final Map<String, Map<String, String>> activeInjections = new HashMap<>();

    public InjectionService(@Lazy CredentialVault vault) {
        this.vault = vault;
    }

    /** Inject credentials required by a tool execution request into the sandbox environment. */
    public synchronized Map<String, String> injectForExecution(ToolExecutionRequest request) {
        Set<String> requiredCreds = request.getRequiredCredentials();
        if (requiredCreds.isEmpty()) {
            return Map.of();
        }

        Map<String, String> resolved = new HashMap<>();
        List<String> missing = new ArrayList<>();

        for (String credKey : requiredCreds) {
            Optional<String> value = vault.retrieve(credKey);
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
    public synchronized void releaseInjection(String requestId) {
        Map<String, String> injected = activeInjections.remove(requestId);
        if (injected != null) {
            injected.clear();
            log.debug("vault Injection: Released injection session '{}'", requestId);
        }
    }

    /** Get active injections for a request (keys only, not values). */
    public synchronized Set<String> getActiveInjectionKeys(String requestId) {
        Map<String, String> injection = activeInjections.get(requestId);
        return injection != null ? injection.keySet() : Set.of();
    }

    public synchronized int getActiveInjectionCount() {
        return activeInjections.size();
    }

    /** Validate that a set of credential keys are available in the vault. */
    public synchronized boolean validateCredentialsAvailable(Set<String> credentialKeys) {
        for (String key : credentialKeys) {
            try {
                if (vault.retrieve(key).isEmpty()) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }
}
