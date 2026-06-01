package top.focess.veto.vault;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for C8 Local Credential Vault.
 */
@Configuration
@ConfigurationProperties(prefix = "veto.vault")
public class CredentialVaultConfiguration {

    private String storePath = "./vault/credentials.enc";
    private String masterKeyEnv = "VETO_VAULT_KEY";
    private int keyDerivationIterations = 600000;

    /**
     * Returns the path to the credential store file.
     *
     * @return the store path
     */
    public String getStorePath() {
        return storePath;
    }

    /**
     * Sets the path to the credential store file.
     *
     * @param storePath the store path to set
     */
    public void setStorePath(String storePath) {
        this.storePath = storePath;
    }

    /**
     * Returns the environment variable name for the master key.
     *
     * @return the master key environment variable name
     */
    public String getMasterKeyEnv() {
        return masterKeyEnv;
    }

    /**
     * Sets the environment variable name for the master key.
     *
     * @param masterKeyEnv the master key environment variable name to set
     */
    public void setMasterKeyEnv(String masterKeyEnv) {
        this.masterKeyEnv = masterKeyEnv;
    }

    /**
     * Returns the number of iterations for key derivation (PBKDF2).
     *
     * @return the number of iterations
     */
    public int getKeyDerivationIterations() {
        return keyDerivationIterations;
    }

    /**
     * Sets the number of iterations for key derivation (PBKDF2).
     *
     * @param keyDerivationIterations the number of iterations to set
     */
    public void setKeyDerivationIterations(int keyDerivationIterations) {
        this.keyDerivationIterations = keyDerivationIterations;
    }
}
