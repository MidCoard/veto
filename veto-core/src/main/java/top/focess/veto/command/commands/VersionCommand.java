package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.VetoVersion;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.Version;

/**
 * Reports the product versions of the connected components.
 *
 * <p>Prints the backend ({@code veto-core}) version - a build-time constant - alongside the
 * connecting terminal's ({@code veto-terminal}) version, which the terminal reported during the IPC
 * {@link top.focess.veto.contract.IpcFrame.Hello} handshake. Available to everyone; version
 * information is not sensitive.
 */
public class VersionCommand extends VetoCommand {

    public VersionCommand() {
        super("version", "Show veto-core and veto-terminal versions", "ver");
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    Version client = s.clientProductVersion();
                    s.output("veto-core: " + VetoVersion.VERSION);
                    s.output("veto-terminal: " + client);
                    return CommandResult.ALLOW;
                });
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of("/version - Show veto-core and veto-terminal versions");
    }
}
