package top.focess.veto.terminal;

import top.focess.command.CommandPermission;
import top.focess.command.CommandSender;

/**
 * A terminal user who sends commands. Tracks authentication state.
 */
public class VetoCommandSender extends CommandSender {

    private String username;
    private boolean authenticated;

    public VetoCommandSender() {
        super(CommandPermission.MEMBER);
    }

    public void authenticate(String username) {
        this.username = username;
        this.authenticated = true;
    }

    public void deauthenticate() {
        this.username = null;
        this.authenticated = false;
    }

    public String getUsername() {
        return username;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }
}
