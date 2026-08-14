package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.security.SignupPolicy;
import top.focess.veto.security.UserAdminService;
import top.focess.veto.vault.UserEntity;
import top.focess.veto.vault.UserRegistry;

/**
 * Admin account-management command ({@code /user}). Available in multi-user signup modes ({@code
 * public}/{@code invite}) and restricted to administrators. Deletion cascades the user's patterns,
 * sessions, agents, and vault via {@link UserAdminService}.
 */
public class UserAdminCommand extends VetoCommand {

    private final @NonNull UserAdminService admin;
    private final @NonNull SignupPolicy policy;

    public UserAdminCommand(@NonNull UserAdminService admin, @NonNull SignupPolicy policy) {
        super("user", "Manage user accounts (admin)", "users");
        this.admin = admin;
        this.policy = policy;
    }

    @Override
    public void init() {
        // /user is admin-only and only meaningful in multi-user modes. Folding both into the
        // executor-permission predicate hides the command from non-admin senders (and entirely
        // under solo) in /help and tab-completion, and routes a non-admin invocation to
        // COMMAND_NOT_FOUND ("Unknown command") instead of leaking "Administrator only." The
        // predicate captures `this` and reads admin/policy lazily, so it is safe to install here
        // during super()/init() before those fields are assigned in the constructor body.
        setExecutorPermission(
                s ->
                        s instanceof VetoCommandSender vs
                                && vs.isLoggedIn()
                                && policy.multiUser()
                                && admin.isAdmin(vs.requireUsername()));

        // /user create <name> [admin]
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    String name = requiredArg(args.get("name"), "name");
                    boolean asAdmin = "admin".equalsIgnoreCase(args.get("role"));
                    String pw = s.input("Password for " + name + ":", true);
                    if (pw == null) {
                        s.output("Cancelled.");
                        return CommandResult.REFUSE;
                    }
                    if (pw.isEmpty()) {
                        s.output("Password cannot be empty.");
                        return CommandResult.REFUSE;
                    }
                    try {
                        admin.create(
                                name,
                                pw,
                                asAdmin ? UserRegistry.Role.ADMIN : UserRegistry.Role.USER);
                    } catch (IllegalArgumentException e) {
                        s.output(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                    s.output("User '" + name + "' created (" + (asAdmin ? "ADMIN" : "USER") + ").");
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

                    String name = requiredArg(args.get("name"), "name");
                    if (name.equals(s.requireUsername())) {
                        s.output("Cannot delete your own account.");
                        return CommandResult.REFUSE;
                    }
                    boolean exists =
                            admin.listAll().stream().anyMatch(u -> u.getUsername().equals(name));
                    if (!exists) {
                        s.output("No such user: " + name);
                        return CommandResult.REFUSE;
                    }
                    if (admin.isAdmin(name) && admin.adminCount() <= 1) {
                        s.output("Cannot delete the last administrator account.");
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
                    admin.deleteUser(name);
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

                    List<UserEntity> all = admin.listAll();
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

                    String name = requiredArg(args.get("name"), "name");
                    String pw = s.input("New password for " + name + ":", true);
                    if (pw == null) {
                        s.output("Cancelled.");
                        return CommandResult.REFUSE;
                    }
                    if (pw.isEmpty()) {
                        s.output("Password cannot be empty.");
                        return CommandResult.REFUSE;
                    }
                    try {
                        admin.setPassword(name, pw);
                    } catch (IllegalArgumentException e) {
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
