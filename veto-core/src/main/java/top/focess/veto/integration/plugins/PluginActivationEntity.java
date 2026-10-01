package top.focess.veto.integration.plugins;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.NonNull;

/** Administrator's desired plugin state for the next backend start. */
@Entity
@Table(name = "plugin_activation")
public class PluginActivationEntity {
    @Id private @NonNull String id = "";

    @Column(nullable = false)
    private boolean enabled;

    protected PluginActivationEntity() {}

    public PluginActivationEntity(@NonNull String id, boolean enabled) {
        this.id = id;
        this.enabled = enabled;
    }

    public @NonNull String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
