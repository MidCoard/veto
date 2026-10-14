package top.focess.veto.command;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.NonNull;
import top.focess.command.AbstractCommandSender;
import top.focess.command.CommandPermission;
import top.focess.veto.contract.Version;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.vault.UserEntity;

/** Terminal command-library adapter. Identity, input and output belong to its connection. */
public final class VetoCommandSender extends AbstractCommandSender {
    private final @NonNull ProtocolConnection connection;
    private final @NonNull Version clientProductVersion;
    private final @NonNull String cwd;

    public VetoCommandSender(
            @NonNull ProtocolConnection connection,
            @NonNull Version clientProductVersion,
            @NonNull String cwd) {
        super(CommandPermission.EVERYONE);
        this.connection = connection;
        this.clientProductVersion = clientProductVersion;
        this.cwd = cwd;
    }

    public @NonNull ProtocolConnection connection() {
        return connection;
    }

    public String username() {
        return connection.username();
    }

    public UUID userId() {
        return connection.userId();
    }

    public void setUser(UserEntity user) {
        connection.setUser(user);
    }

    public boolean isLoggedIn() {
        return userId() != null;
    }

    public @NonNull String terminalId() {
        return connection.id();
    }

    public @NonNull Version clientProductVersion() {
        return clientProductVersion;
    }

    public @NonNull String cwd() {
        return cwd;
    }

    public @NonNull UUID requireUserId() {
        var user = userId();
        if (user == null) throw new IllegalStateException("Command requires an authenticated user");
        return user;
    }

    public @NonNull String requireUsername() {
        var name = username();
        if (name == null) throw new IllegalStateException("Command requires an authenticated user");
        return name;
    }

    @Override
    public void output(String message) {
        if (message != null && !message.isEmpty()) connection.output(message);
    }

    // The command library declares a non-null return, but cancellation is a nullable answer.
    @SuppressWarnings("override.return")
    @Override
    public String input() {
        return input("", false);
    }

    public String input(@NonNull String text, boolean mask) {
        try {
            return inputAsync(text, mask, 90_000).join();
        } catch (CancellationException cancelled) {
            return null;
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof CancellationException) return null;
            throw failed;
        }
    }

    @Override
    public @NonNull CompletableFuture<String> inputAsync(long timeoutMillis) {
        return inputAsync("", false, timeoutMillis);
    }

    public @NonNull CompletableFuture<String> inputAsync(
            @NonNull String text, boolean mask, long timeoutMillis) {
        return connection.input(text, mask, timeoutMillis);
    }
}
