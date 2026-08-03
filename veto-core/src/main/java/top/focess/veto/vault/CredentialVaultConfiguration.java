package top.focess.veto.vault;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for the keystead-backed credential vault. */
@Configuration
@ConfigurationProperties(prefix = "veto.vault")
public class CredentialVaultConfiguration {

    /**
     * Base directory for all vault files (keystead vaults live under {@code {vaultHome}/keystead}).
     */
    private @NonNull String vaultHome = expandTilde("~/.veto");

    /** Returns the base directory for all vault files. */
    public @NonNull String getVaultHome() {
        return vaultHome;
    }

    /** Sets the base directory for all vault files. */
    public void setVaultHome(@NonNull String vaultHome) {
        this.vaultHome = expandTilde(vaultHome);
    }

    private static @NonNull String expandTilde(@NonNull String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
