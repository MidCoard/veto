package top.focess.veto.agent.capability;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.credentials.ImportedCredentialLease;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.integration.plugins.IsolatedExecutions;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.KeysteadVault;

/** Host authority for cooperative, invocation-confined access to imported credentials. */
@Component
public final class ImportedCredentialLeases {
    private static final @NonNull ConcurrentHashMap<ToolCallContext, Set<Lease>> ACTIVE =
            new ConcurrentHashMap<>();
    private final @NonNull KeysteadVault vault;
    private final @NonNull SessionRepository sessions;
    private final @NonNull ObjectProvider<PluginManager> plugins;
    private final @NonNull ObjectProvider<SessionPlugins> selections;

    /** Creates the lease authority over the given vault, session, and plugin state. */
    public ImportedCredentialLeases(
            @NonNull KeysteadVault vault,
            @NonNull SessionRepository sessions,
            @NonNull ObjectProvider<PluginManager> plugins,
            @NonNull ObjectProvider<SessionPlugins> selections) {
        this.vault = vault;
        this.sessions = sessions;
        this.plugins = plugins;
        this.selections = selections;
    }

    /** Opens an invocation-confined lease for the credential reference in the named argument. */
    public @NonNull ImportedCredentialLease open(
            @NonNull String argument, @NonNull String service) {
        var context = CapabilityAccess.require(ToolCapability.NETWORK_EGRESS);
        IsolatedExecutions.requireNonIsolatedParent(context);
        var reference = context.executionPermit().call().args().get(argument);
        if (!(reference instanceof String ref)
                || !ref.matches("cred_[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                || !service.matches("[a-z][a-z0-9._-]{0,63}"))
            throw new SecurityException("No approved imported credential binding");
        var lease = new Lease(context, ref, service);
        lease.check();
        ACTIVE.computeIfAbsent(context, ignored -> ConcurrentHashMap.newKeySet()).add(lease);
        return lease;
    }

    /** Closes every lease still open for a finished invocation. */
    public static void releaseInvocation(ToolCallContext context) {
        if (context == null) return;
        var leases = ACTIVE.remove(context);
        if (leases != null) leases.forEach(Lease::close);
    }

    private final class Lease implements ImportedCredentialLease {
        private final @NonNull ToolCallContext context;
        private final @NonNull String reference;
        private final @NonNull String service;
        private final @NonNull Thread thread = Thread.currentThread();
        private volatile boolean closed;

        private Lease(
                @NonNull ToolCallContext context,
                @NonNull String reference,
                @NonNull String service) {
            this.context = context;
            this.reference = reference;
            this.service = service;
        }

        private @NonNull String check() {
            if (closed
                    || Thread.currentThread() != thread
                    || thread.isInterrupted()
                    || CapabilityAccess.require(ToolCapability.NETWORK_EGRESS) != context)
                throw new SecurityException("Credential invocation is no longer active");
            var owner = context.owner();
            var session = context.sessionId();
            if (owner == null
                    || session == null
                    || !vault.isUnlocked(owner)
                    || !sessions.findById(session.toString())
                            .map(row -> owner.equals(row.getOwner()))
                            .orElse(false))
                throw new SecurityException("Credential session is unavailable");
            var binding = context.executionPermit().remoteServerName();
            if (binding != null) {
                var manager = plugins.getIfAvailable();
                var selected = selections.getIfAvailable();
                if (manager == null
                        || selected == null
                        || manager.plugins().stream()
                                .noneMatch(
                                        plugin ->
                                                binding.equals(plugin.bindingId())
                                                        && plugin.state() == PluginState.ACTIVE
                                                        && selected.includes(
                                                                session.toString(),
                                                                plugin.identity().id())))
                    throw new SecurityException("Credential plugin is unavailable");
            }
            return owner;
        }

        public void use(@NonNull Consumer<char @NonNull []> operation) {
            vault.withImportedCredential(
                    check(),
                    reference,
                    service,
                    value -> {
                        check();
                        char[] copy = value.clone();
                        try {
                            operation.accept(copy);
                        } finally {
                            Arrays.fill(copy, '\0');
                        }
                    });
        }

        public void close() {
            closed = true;
            var leases = ACTIVE.get(context);
            if (leases != null) {
                leases.remove(this);
                if (leases.isEmpty()) ACTIVE.remove(context, leases);
            }
        }
    }
}
