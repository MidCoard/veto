package top.focess.veto.security;

/**
 * How accounts may be created, configured via {@code veto.security.signup.mode}.
 *
 * <ul>
 *   <li>{@link #SOLO} - single-user: the first {@code /signup} wins (becomes ADMIN), then blocked.
 *   <li>{@link #PUBLIC} - multi-user: anonymous self-signup (first ADMIN, rest USER).
 *   <li>{@link #INVITE} - multi-user: no anonymous signup; the bootstrap admin provisions the rest
 *       via {@code /user create}.
 * </ul>
 *
 * <p>Under {@code deployer-policy=TENANT} only the multi-user modes ({@link #PUBLIC}/{@link
 * #INVITE}) are valid; {@link SignupPolicy} validates this at startup.
 */
public enum SignupMode {
    SOLO,
    PUBLIC,
    INVITE;

    /** Whether this mode permits more than one account. */
    public boolean multiUser() {
        return this == PUBLIC || this == INVITE;
    }

    /** Whether unauthenticated self-signup is ever allowed (after the bootstrap admin exists). */
    public boolean selfSignupAllowed() {
        return this == SOLO || this == PUBLIC;
    }
}
