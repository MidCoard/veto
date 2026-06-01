package top.focess.veto.vault;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.model.ToolExecutionRequest;

/**
 * C8 Injection Service - injects credentials into C6 Sandbox temporarily during execution.
 * Credentials are injected only for the duration of the tool execution and are never exposed to C3
 * (Communication Bus).
 */
@Component
public class InjectionService {

    private static final Logger log = LoggerFactory.getLogger(InjectionService.class);

    private final SecureStore secureStore;

    // Active injection sessions - cleared after use
    private final Map<String, Map<String, String>> activeInjections = new HashMap<>();

    /**
     * Constructs a new InjectionService with the specified secure store.
     *
     * @param secureStore the secure store to retrieve credentials from for injection
     */
    public InjectionService(SecureStore secureStore) {
        this.secureStore = secureStore;
    }

    /**
     * Inject credentials required by a tool execution request into the sandbox environment. The
     * credentials are passed through a temporary, ephemeral context.
     *
     * @param request The tool execution request specifying required credentials
     * @return A map of credential name -> resolved value for injection
     */
    public synchronized Map<String, String> injectForExecution(ToolExecutionRequest request) {
        Set<String> requiredCreds = request.getRequiredCredentials();
        if (requiredCreds.isEmpty()) {
            return Map.of();
        }

        Map<String, String> resolved = new HashMap<>();
        List<String> missing = new ArrayList<>();

        for (String credKey : requiredCreds) {
            Optional<String> value = secureStore.retrieve(credKey);
            if (value.isPresent()) {
                resolved.put(credKey, value.get());
            } else {
                missing.add(credKey);
            }
        }

        // Track active injection session
        activeInjections.put(request.getId(), new HashMap<>(resolved));

        if (!missing.isEmpty()) {
            log.warn("C8 Injection: Missing credentials for request {}: {}", request.getId(), missing);
        }

        log.info(
                "C8 Injection: Injected {} credentials for request '{}'",
                resolved.size(),
                request.getCapabilityName());

        return Collections.unmodifiableMap(resolved);
    }

    /**
     * Release injected credentials after execution completes. Ensures credentials are not retained in
     * memory.
     *
     * @param requestId the unique ID of the request to release
     */
    public synchronized void releaseInjection(String requestId) {
        Map<String, String> injected = activeInjections.remove(requestId);
        if (injected != null) {
            // Clear the values to prevent memory retention
            injected.clear();
            log.debug("C8 Injection: Released injection session '{}'", requestId);
        }
    }

    /**
     * Get active injections for a request (without values - just the keys).
     *
     * @param requestId the unique ID of the request
     * @return a set of credential keys that are currently injected
     */
    public synchronized Set<String> getActiveInjectionKeys(String requestId) {
        Map<String, String> injection = activeInjections.get(requestId);
        return injection != null ? injection.keySet() : Set.of();
    }

    /**
     * Returns the number of active injection sessions.
     *
     * @return the active injection count
     */
    public synchronized int getActiveInjectionCount() {
        return activeInjections.size();
    }

    /**
     * Validate that a set of credential keys are available in the secure store.
     *
     * @param credentialKeys the set of keys to check
     * @return true if all keys exist, false otherwise
     */
    public synchronized boolean validateCredentialsAvailable(Set<String> credentialKeys) {
        for (String key : credentialKeys) {
            if (!secureStore.exists(key)) {
                return false;
            }
        }
        return true;
    }
}
