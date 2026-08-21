package top.focess.veto.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import top.focess.command.CommandManager;
import top.focess.command.CommandPermission;
import top.focess.command.CommandResult;
import top.focess.command.ExecutionResult;
import top.focess.veto.VetoVersion;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.Version;

class VersionCommandTest {

    @Test
    void reportsVersionsLicenseAndSource() {
        VetoCommandSender sender = mock(ToolDocs.nonNullClass(VetoCommandSender.class));
        when(sender.hasPermission(any(ToolDocs.nonNullClass(CommandPermission.class))))
                .thenReturn(true);
        when(sender.clientProductVersion()).thenReturn(Version.parse("2.3.4"));

        CommandManager manager = new CommandManager();
        manager.register(new VersionCommand());

        ExecutionResult result = manager.dispatch(sender, "version");

        assertEquals(CommandResult.ALLOW, result.result());
        verify(sender).output("veto-core: " + VetoVersion.VERSION);
        verify(sender).output("veto-terminal: 2.3.4");
        verify(sender).output("license: AGPL-3.0-only");
        verify(sender).output("source: https://github.com/MidCoard/veto");
    }
}
