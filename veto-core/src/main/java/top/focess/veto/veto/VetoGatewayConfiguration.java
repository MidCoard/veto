package top.focess.veto.veto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for outbound payload filtering. */
@Configuration
@ConfigurationProperties(prefix = "veto.veto-gateway")
public class VetoGatewayConfiguration {

    private boolean enabled = true;
    private boolean enforceStructuralConstraints = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnforceStructuralConstraints() {
        return enforceStructuralConstraints;
    }

    public void setEnforceStructuralConstraints(boolean enforceStructuralConstraints) {
        this.enforceStructuralConstraints = enforceStructuralConstraints;
    }
}
