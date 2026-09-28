package top.focess.veto.api.event;

/**
 * Broadcast after the host publishes a new service directory. Plugins can discover protocols and
 * replay optional registrations; this event carries no service implementation or authority.
 */
public final class ServiceDirectoryChangedEvent extends Event {
    /** Creates the notification after a successful directory publication. */
    public ServiceDirectoryChangedEvent() {}
}
