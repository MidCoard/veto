package top.focess.veto.agent.mcp.tools;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeMcpTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolSecurity;

/**
 * {@code run_command} — the special tool that executes arbitrary external processes. Transcribed
 * from.
 *
 * <p>This tool is registered as a {@link NativeMcpTool} so its schema is advertised in the
 * manifest, but its execution does <b>not</b> run a process in the host JVM: {@link
 * top.focess.veto.agent.mcp.McpEngine#execute} special-cases {@code run_command} and routes it
 * through the session's {@link top.focess.veto.sandbox.SandboxSubstrate} (no shell, argv[] direct
 * exec, cwd locked, Veto-controlled chaining). Consequently {@link #execute} is never invoked by
 * the engine and throws to make the special-casing explicit.
 */
@Component
@ToolSecurity(risk = RiskCategory.SHELL_EXEC)
public final class RunCommandTool implements NativeMcpTool<RunCommandTool.Args> {

    /** A single discrete command in the chain. */
    public record CommandInput(
            @Doc(
                            "Binary name (resolved against the exec allowlist), e.g. 'gradle'. Not a shell string.")
                    String executable,
            @Doc("argv array. Glob/env expansion is done by Veto, not a shell.")
                    List<String> args) {}

    public record Args(
            @SecurityHint(ParamCategory.SHELL_COMMAND)
                    @Doc(
                            "Discrete commands; Veto connects them per `connect`. No shell, no chaining operators in input.")
                    List<CommandInput> commands,
            @SecurityHint(ParamCategory.FILESYSTEM_PATH)
                    @Doc("Working directory; must be under an allowed root (Gateway-checked).")
                    String cwd,
            @Doc(
                            /* annotation was: @Nullable */
                            "How Veto connects the commands: STOP_ON_FAILURE (default), RUN_ALL, or PIPE.")
                    String connect) {}

    @Override
    public String getName() {
        return "run_command";
    }

    @Override
    public String getDescription() {
        return "Run one or more commands inside the sandbox. The model lists discrete commands; "
                + "Veto connects them per `connect`.";
    }

    @Override
    public Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        throw new UnsupportedOperationException(
                "run_command execution is routed through the Sandbox substrate by McpEngine.execute, "
                        + "not through NativeMcpTool.execute.");
    }
}
