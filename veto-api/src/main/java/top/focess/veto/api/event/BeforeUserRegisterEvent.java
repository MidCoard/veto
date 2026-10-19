package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/**
 * Synchronous registration decision before any account or vault write. The host validates admission
 * first and reads the final cancellation state after delivery. Passwords and mutable authority are
 * not exposed. Ordinary listener failures follow {@link Event}'s containment contract.
 */
public final class BeforeUserRegisterEvent extends Event implements Cancellable {
    private final @NonNull String username;
    private final @NonNull String role;
    private final Scope.@NonNull GlobalScope scope = new Scope.GlobalScope();
    private boolean cancelled;

    /**
     * Creates the decision for a validated username and host-approved role.
     *
     * @param username validated proposed login name
     * @param role role admitted by the host
     */
    public BeforeUserRegisterEvent(@NonNull String username, @NonNull String role) {
        this.username = username;
        this.role = role;
    }

    @Override
    public Scope.@NonNull GlobalScope scope() {
        return scope;
    }

    /**
     * The proposed login name; observing it grants no account authority.
     *
     * @return the proposed login name
     */
    public @NonNull String username() {
        return username;
    }

    /**
     * The role already admitted by the host; listeners cannot change it.
     *
     * @return the host-approved role
     */
    public @NonNull String role() {
        return role;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
