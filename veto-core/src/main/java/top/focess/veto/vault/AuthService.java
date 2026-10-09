package top.focess.veto.vault;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.i18n.Msg;
import top.focess.veto.security.SignupMode;
import top.focess.veto.security.SignupPolicy;
import top.focess.veto.vault.LoginSessionManager.LoginSession;

/** Complete authentication workflows shared by HTTP and terminal adapters. */
@Service
public class AuthService {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.vault.AuthService");
    private final @NonNull UserRegistry users;
    private final @NonNull LoginSessionManager sessions;
    private final @NonNull KeysteadVault vault;
    private final @NonNull AuthLifecycleManager lifecycle;
    private final @NonNull UserAdminService accounts;
    private final @NonNull SignupPolicy policy;

    public AuthService(
            @NonNull UserRegistry users,
            @NonNull LoginSessionManager sessions,
            @NonNull KeysteadVault vault,
            @NonNull AuthLifecycleManager lifecycle,
            @NonNull UserAdminService accounts,
            @NonNull SignupPolicy policy) {
        this.users = users;
        this.sessions = sessions;
        this.vault = vault;
        this.lifecycle = lifecycle;
        this.accounts = accounts;
        this.policy = policy;
    }

    /** A completed HTTP login, whose token was published under the lifecycle boundary. */
    public record Login(@NonNull UserEntity user, @NonNull String token) {}

    /** Informational account/vault/login-session status; fields may change independently. */
    public record Status(
            boolean setupNeeded,
            boolean vaultLocked,
            int activeSessions,
            @NonNull Optional<LoginSession> login) {}

    public @NonNull Login setup(String username, String password) {
        username = validateUsername(username);
        password = validatePassword(password);
        try (var _ = lifecycle.locks().exclusive()) {
            if (users.anyUserExists())
                throw reject(AuthException.Kind.CONFLICT, "error.auth.alreadySetup");
            var user = create(username, password, UserRegistry.Role.ADMIN);
            unlock(user, password, true);
            return clientLogin(user);
        }
    }

    public @NonNull Login login(String username, String password) {
        username = requiredCredential(username);
        password = requiredCredential(password);
        try (var _ = lifecycle.locks().shared()) {
            var identity = loginIdentity(username, password);
            try (var _ = lifecycle.locks().account(identity)) {
                var user = authenticate(username, password);
                unlock(user, password, false);
                return clientLogin(user);
            }
        }
    }

    public @NonNull UserEntity loginTerminal(
            @NonNull VetoCommandSender sender, String username, String password) {
        username = requiredCredential(username);
        password = requiredCredential(password);
        try (var _ = lifecycle.locks().shared()) {
            var identity = loginIdentity(username, password);
            try (var _ = lifecycle.locks().account(identity)) {
                var user = authenticate(username, password);
                unlock(user, password, false);
                sender.setUser(user);
                return user;
            }
        }
    }

    public @NonNull UserEntity signupTerminal(
            @NonNull VetoCommandSender sender, String username, String password) {
        username = validateUsername(username);
        password = validatePassword(password);
        try (var _ =
                users.adminCount() == 0
                        ? lifecycle.locks().exclusive()
                        : lifecycle.locks().shared()) {
            if (sender.isLoggedIn())
                throw reject(AuthException.Kind.CONFLICT, "error.auth.alreadyLoggedIn");
            boolean bootstrap = users.adminCount() == 0;
            if (!bootstrap && policy.mode() != SignupMode.PUBLIC)
                throw reject(AuthException.Kind.FORBIDDEN, "error.auth.signupDisabled");
            var user =
                    create(
                            username,
                            password,
                            bootstrap ? UserRegistry.Role.ADMIN : UserRegistry.Role.USER);
            try (var _ = lifecycle.locks().account(user.getUserId())) {
                unlock(user, password, true);
                sender.setUser(user);
                return user;
            }
        }
    }

    public @NonNull LoginSession logout(@NonNull String token) {
        var identity = requireSession(token).userId();
        try (var _ = lifecycle.locks().account(identity)) {
            var login = requireSession(token);
            sessions.revokeToken(token);
            if (!sessions.hasLoginSessions(login.userId())) lifecycle.logout(login.userId());
            return login;
        }
    }

    public void logoutTerminal(@NonNull VetoCommandSender sender, @NonNull UUID actor) {
        try (var _ = lifecycle.locks().account(actor)) {
            requireTerminalActor(sender, actor);
            lifecycle.logout(actor);
            sender.setUser(null);
        }
    }

    public @NonNull Status status(String token) {
        return new Status(
                !users.anyUserExists(),
                !vault.isUnlocked(),
                sessions.activeLoginSessionCount(),
                sessions.validateToken(token == null ? "" : token));
    }

    public @NonNull UserEntity createUser(
            @NonNull String token, String username, String password, String role) {
        var actor = requireSession(token).userId();
        try (var _ = lifecycle.locks().account(actor)) {
            requireAdministrator(requireSession(token).userId());
            username = validateUsername(username);
            password = validatePassword(password);
            return create(username, password, normalizeRole(role));
        }
    }

    public @NonNull UserEntity createUser(
            @NonNull VetoCommandSender sender,
            @NonNull UUID actor,
            String username,
            String password,
            String role) {
        try (var _ = lifecycle.locks().account(actor)) {
            requireTerminalAdministrator(sender, actor);
            username = validateUsername(username);
            password = validatePassword(password);
            return create(username, password, normalizeRole(role));
        }
    }

    /** A command-visibility check; authoritative admission still occurs inside each operation. */
    public boolean canManageUsers(@NonNull VetoCommandSender sender) {
        var actor = sender.userId();
        return actor != null && policy.multiUser() && users.isAdmin(actor);
    }

    public @NonNull List<UserEntity> listUsers(
            @NonNull VetoCommandSender sender, @NonNull UUID actor) {
        try (var _ = lifecycle.locks().account(actor)) {
            requireTerminalAdministrator(sender, actor);
            return accounts.listAll();
        }
    }

    /** Resolves a confirmation target by canonical identity, which survives the input wait. */
    public @NonNull UserEntity findUser(
            @NonNull VetoCommandSender sender, @NonNull UUID actor, @NonNull String username) {
        try (var _ = lifecycle.locks().account(actor)) {
            requireTerminalAdministrator(sender, actor);
            return findUser(username);
        }
    }

    public void deleteUser(
            @NonNull VetoCommandSender sender, @NonNull UUID actor, @NonNull UUID target) {
        try (var _ = lifecycle.locks().exclusive()) {
            requireTerminalAdministrator(sender, actor);
            if (actor.equals(target))
                throw reject(AuthException.Kind.FORBIDDEN, "error.auth.deleteSelf");
            try {
                accounts.deleteUser(target);
            } catch (IllegalArgumentException rejected) {
                var message = rejected.getMessage();
                throw new AuthException(
                        AuthException.Kind.CONFLICT,
                        message == null ? Msg.get("error.auth.noSuchUser", target) : message,
                        rejected);
            }
        }
    }

    public void setPassword(
            @NonNull VetoCommandSender sender,
            @NonNull UUID actor,
            @NonNull UUID target,
            String password) {
        try (var _ = lifecycle.locks().account(actor, target)) {
            requireTerminalAdministrator(sender, actor);
            password = validatePassword(password);
            try {
                accounts.setPassword(target, password);
            } catch (IllegalArgumentException rejected) {
                var message = rejected.getMessage();
                throw new AuthException(
                        AuthException.Kind.CONFLICT,
                        message == null ? Msg.get("error.auth.passwordChangeFailed") : message,
                        rejected);
            }
        }
    }

    private @NonNull UserEntity findUser(@NonNull String username) {
        return users.findByUsername(username)
                .orElseThrow(
                        () ->
                                new AuthException(
                                        AuthException.Kind.CONFLICT,
                                        Msg.get("error.auth.noSuchUser", username)));
    }

    /** Resolve a lock key without verifying credentials outside the account boundary. */
    private @NonNull UUID loginIdentity(@NonNull String username, @NonNull String password) {
        var account = users.findByUsername(username);
        if (account.isPresent()) return account.get().getUserId();
        // Keep the registry's dummy password hash for unknown usernames.
        var ignoredAuthentication = users.authenticate(username, password);
        log.debug(
                "Unknown-username authentication completed; account appeared: {}",
                ignoredAuthentication.isPresent());
        throw reject(AuthException.Kind.INVALID_CREDENTIALS, "error.auth.invalidCredentials");
    }

    private @NonNull UserEntity authenticate(@NonNull String username, @NonNull String password) {
        return users.authenticate(username, password)
                .orElseThrow(
                        () ->
                                reject(
                                        AuthException.Kind.INVALID_CREDENTIALS,
                                        "error.auth.invalidCredentials"));
    }

    private @NonNull Login clientLogin(@NonNull UserEntity user) {
        return new Login(user, sessions.createLoginSession(user.getUserId(), user.getUsername()));
    }

    private @NonNull UserEntity create(
            @NonNull String username, @NonNull String password, @NonNull String role) {
        try {
            return accounts.create(username, password, role);
        } catch (IllegalArgumentException duplicate) {
            throw new AuthException(
                    AuthException.Kind.CONFLICT,
                    Msg.get("error.auth.userExists", username),
                    duplicate);
        }
    }

    private void unlock(@NonNull UserEntity user, @NonNull String password, boolean signup) {
        try {
            if (signup) lifecycle.signup(user, password);
            else lifecycle.login(user, password);
        } catch (CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            log.error("Account vault unlock failed for {}", user.getUserId(), failure);
            throw new AuthException(
                    AuthException.Kind.INTERNAL, Msg.get("error.auth.loginFailed"), failure);
        }
    }

    private @NonNull LoginSession requireSession(@NonNull String token) {
        return sessions.validateToken(token)
                .orElseThrow(
                        () ->
                                reject(
                                        AuthException.Kind.INVALID_SESSION,
                                        "error.auth.invalidSession"));
    }

    private void requireAdministrator(@NonNull UUID actor) {
        if (!users.isAdmin(actor))
            throw reject(AuthException.Kind.FORBIDDEN, "error.auth.adminRequired");
    }

    private void requireTerminalActor(@NonNull VetoCommandSender sender, @NonNull UUID actor) {
        if (!actor.equals(sender.userId()))
            throw reject(AuthException.Kind.INVALID_SESSION, "error.auth.actorChanged");
    }

    private void requireTerminalAdministrator(
            @NonNull VetoCommandSender sender, @NonNull UUID actor) {
        requireTerminalActor(sender, actor);
        if (!policy.multiUser())
            throw reject(AuthException.Kind.FORBIDDEN, "error.auth.adminRequired");
        requireAdministrator(actor);
    }

    private static @NonNull String requiredCredential(String value) {
        if (value == null || value.isEmpty())
            throw reject(AuthException.Kind.INVALID_INPUT, "error.auth.credentialsRequired");
        return value;
    }

    private static @NonNull String validateUsername(String username) {
        var value = requiredCredential(username);
        if (!UserRegistry.isValidUsername(value))
            throw reject(AuthException.Kind.INVALID_INPUT, "error.auth.invalidUsername");
        return value;
    }

    private static @NonNull String validatePassword(String password) {
        var value = requiredCredential(password);
        if (value.length() < 8)
            throw reject(AuthException.Kind.INVALID_INPUT, "error.auth.passwordTooShort");
        return value;
    }

    private static @NonNull String normalizeRole(String role) {
        var normalized =
                role == null ? UserRegistry.Role.USER : role.trim().toUpperCase(Locale.ROOT);
        if (!UserRegistry.isValidRole(normalized))
            throw reject(AuthException.Kind.INVALID_INPUT, "error.auth.invalidRole");
        return normalized;
    }

    private static @NonNull AuthException reject(
            AuthException.@NonNull Kind kind, @NonNull String key) {
        return new AuthException(kind, Msg.get(key));
    }
}
