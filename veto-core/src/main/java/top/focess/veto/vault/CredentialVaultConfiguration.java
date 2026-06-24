package top.focess.veto.vault;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for vault Local Credential Vault. */
@Configuration
@ConfigurationProperties(prefix = "veto.vault")
public class CredentialVaultConfiguration {

    /** Base directory for all vault files. */
    private String vaultHome = expandTilde("~/.veto");

    /** Number of iterations for key derivation (Argon2id). */
    private int keyDerivationIterations = 3;

    /** Returns the base directory for all vault files. */
    public String getVaultHome() {
        return vaultHome;
    }

    /** Sets the base directory for all vault files. */
    public void setVaultHome(String vaultHome) {
        this.vaultHome = expandTilde(vaultHome);
    }

    /** Returns the number of iterations for key derivation (Argon2id). */
    public int getKeyDerivationIterations() {
        return keyDerivationIterations;
    }

    /** Sets the number of iterations for key derivation (Argon2id). */
    public void setKeyDerivationIterations(int keyDerivationIterations) {
        this.keyDerivationIterations = keyDerivationIterations;
    }

    /** Derives the path to the encrypted credentials store. */
    public String getStorePath() {
        return vaultHome + "/vault/credentials.enc";
    }

    private static String expandTilde(String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
