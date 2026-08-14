package top.focess.veto.agent.screening;

import org.jspecify.annotations.NonNull;

/**
 * Install-time deployer policy. FULL: no protected set. PROTECT_SENSITIVE: protected set exists.
 */
public enum DeployerPolicy {
    FULL_ACCESS,
    PROTECTED,
    SANDBOXED,
    TENANT;

    /**
     * Parses the policy from a raw config value: {@code null}/blank -> {@link #FULL_ACCESS};
     * case-insensitive name match; otherwise {@link IllegalArgumentException}.
     */
    public static @NonNull DeployerPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FULL_ACCESS;
        }
        var policies = top.focess.veto.util.Nullness.requireNonNull(values());
        for (DeployerPolicy p : policies) {
            if (p.name().equalsIgnoreCase(raw.trim())) {
                return p;
            }
        }
        throw new IllegalArgumentException(
                "Unknown deployer policy '"
                        + raw
                        + "'; expected one of FULL_ACCESS, PROTECTED,"
                        + " SANDBOXED, TENANT");
    }
}
