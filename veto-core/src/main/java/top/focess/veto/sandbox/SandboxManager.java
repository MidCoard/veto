package top.focess.veto.sandbox;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The sole authority for sandbox provisioning/deprovisioning. {@code AgentRunner} never calls
 * Docker/Firecracker/OS-primitive APIs directly..
 *
 * <p>Holds {@link SandboxHandle}s in-memory keyed by {@code sessionId} — never persisted on {@code
 * AgentEntity} (runtime resources are volatile). The substrate is the configured {@link
 * ConstrainedSubprocessSubstrate}; a container/microVM substrate may be swapped in per the
 * deployer's fixed-menu selection.
 */
@Service
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);

    private final @NonNull SandboxSubstrate substrate;
    private final ConcurrentHashMap<String, SandboxHandle> handles = new ConcurrentHashMap<>();

    public
    @NonNull
    SandboxManager(@NonNull ConstrainedSubprocessSubstrate substrate) {
        this.substrate = substrate;
    }

    /** Provisions a sandbox for a session and caches the handle keyed by {@code sessionId}. */
    public @NonNull SandboxHandle provision(
            @NonNull String sessionId, @NonNull Path workspaceRoot) {
        return handles.computeIfAbsent(
                sessionId, k -> substrate.provision(SandboxProfile.defaults(workspaceRoot)));
    }

    /** Returns the cached handle for a session, provisioning a default if absent. */
    public @NonNull SandboxHandle handleFor(@NonNull String sessionId) {
        return handles.computeIfAbsent(
                sessionId,
                k -> {
                    Path root = Path.of(".").toAbsolutePath().normalize();
                    return substrate.provision(SandboxProfile.defaults(root));
                });
    }

    public void deprovision(@NonNull String sessionId) {
        SandboxHandle h = handles.remove(sessionId);
        if (h != null) {
            substrate.deprovision(h);
            log.debug("Sandbox deprovisioned for session {}", sessionId);
        }
    }

    public SandboxSubstrate substrate() {
        return substrate;
    }
}
