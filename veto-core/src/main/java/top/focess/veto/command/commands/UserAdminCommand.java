package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.vault.AuthException;
import top.focess.veto.vault.AuthService;
import top.focess.veto.vault.UserAdminService;
import top.focess.veto.vault.UserEntity;

/**
 * Admin account-management command ({@code /user}). Available in multi-user signup modes ({@code
 * public}/{@code invite}) and restricted to administrators. Deletion cascades the user's patterns,
 * sessions, agents, and vault via {@link UserAdminService}.
 */
public class UserAdminCommand extends VetoCommand {

    private final @NonNull AuthService auth;

    public UserAdminCommand(@NonNull AuthService auth) {
        super("user", "Manage user accounts (admin)", "users");
        this.auth = auth;
    }

    @Override
    public void init() {
        setExecutorPermission(s -> s instanceof VetoCommandSender vs && auth.canManageUsers(vs));

        // /user create <name> [admin]
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    var actor = s.requireUserId();
                    String name = requiredArg(args.get("name"), "name");
                    String role = args.get("role");
                    String pw = s.input("Password for " + name + ":", true);
                    if (pw == null) {
                        s.output("Cancelled.");
                        return CommandResult.REFUSE;
                    }
                    try {
                        var created = auth.createUser(s, actor, name, pw, role);
                        s.output(
                                "User '"
                                        + created.getUsername()
                                        + "' created ("
                                        + created.getRole()
                                        + ").");
                    } catch (AuthException e) {
                        s.output(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                    return CommandResult.ALLOW;
                },
                fixed("create").description("Create a user account"),
                arg("name"),
                opt("role").description("Pass 'admin' to create an administrator"));

        // /user delete <name>
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    var actor = s.requireUserId();
                    String name = requiredArg(args.get("name"), "name");
                    UserEntity target;
                    try {
                        target = auth.findUser(s, actor, name);
                    } catch (AuthException rejected) {
                        s.output(rejected.getMessage());
                        return CommandResult.REFUSE;
                    }
                    String confirm =
                            s.input(
                                    "Delete '"
                                            + name
                                            + "' and all their data? Type 'yes' to confirm:",
                                    false);
                    if (confirm == null || !"yes".equalsIgnoreCase(confirm.trim())) {
                        s.output("Cancelled.");
                        return CommandResult.REFUSE;
                    }
                    try {
                        auth.deleteUser(s, actor, target.getUserId());
                    } catch (AuthException rejected) {
                        s.output(rejected.getMessage());
                        return CommandResult.REFUSE;
                    }
                    s.output("User '" + name + "' deleted.");
                    return CommandResult.ALLOW;
                },
                fixed("delete").description("Delete a user and their data"),
                arg("name"));

        // /user list
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    List<UserEntity> all;
                    var actor = s.requireUserId();
                    try {
                        all = auth.listUsers(s, actor);
                    } catch (AuthException rejected) {
                        s.output(rejected.getMessage());
                        return CommandResult.REFUSE;
                    }
                    if (all.isEmpty()) {
                        s.output("No users.");
                        return CommandResult.ALLOW;
                    }
                    s.output("Users:");
                    for (UserEntity u : all) {
                        s.output(
                                String.format(
                                        "  %-16s %-8s %s",
                                        u.getUsername(), u.getRole(), u.getCreatedAt()));
                    }
                    return CommandResult.ALLOW;
                },
                fixed("list").description("List all users"));

        // /user password <name>
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    var actor = s.requireUserId();
                    String name = requiredArg(args.get("name"), "name");
                    UserEntity target;
                    try {
                        target = auth.findUser(s, actor, name);
                    } catch (AuthException rejected) {
                        s.output(rejected.getMessage());
                        return CommandResult.REFUSE;
                    }
                    String pw = s.input("New password for " + name + ":", true);
                    if (pw == null) {
                        s.output("Cancelled.");
                        return CommandResult.REFUSE;
                    }
                    try {
                        auth.setPassword(s, actor, target.getUserId(), pw);
                    } catch (AuthException e) {
                        s.output(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                    s.output("Password reset for '" + name + "'.");
                    return CommandResult.ALLOW;
                },
                fixed("password").description("Reset a user's password"),
                arg("name"));
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of(
                "/user create <name> [admin] - Create a user account",
                "/user delete <name> - Delete a user and their data",
                "/user list - List all users",
                "/user password <name> - Reset a user's password");
    }
}
