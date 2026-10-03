package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/**
 * Notification after the host publishes a new service directory. Plugins can discover protocols and
 * replay optional registrations; this event carries no service implementation or authority.
 */
public final class ServiceDirectoryChangedEvent extends Event {
    /** Creates the notification after a successful directory publication. */
    public ServiceDirectoryChangedEvent() {}

    /**
     * Returns the global directory identity; observing it grants no authority.
     *
     * @return global directory scope
     */
    @Override
    public Scope.@NonNull GlobalScope scope() {
        return new Scope.GlobalScope();
    }
}
