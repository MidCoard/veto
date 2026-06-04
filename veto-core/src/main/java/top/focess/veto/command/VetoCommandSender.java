package top.focess.veto.command;

import top.focess.command.CommandPermission;
import top.focess.command.CommandSender;

public final class VetoCommandSender extends CommandSender {

    private final String username;

    public VetoCommandSender(String username) {
        super(CommandPermission.ADMINISTRATOR);
        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    public boolean isLoggedIn() {
        return username != null;
    }
}
