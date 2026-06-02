package top.focess.veto.terminal.command;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import top.focess.command.*;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.llm.core.*;
import top.focess.veto.terminal.TerminalContext;

public class SendCommand extends Command {

    private final TerminalContext ctx;

    public SendCommand(TerminalContext ctx) {
        super("send");
        this.ctx = ctx;
    }

    @Override
    public void init() {
        addExecutor(
                (s, d, io) -> {
                    if (!ctx.sender.isAuthenticated()) {
                        io.output("Login first: /login <user> <pass>");
                        return CommandResult.ALLOW;
                    }
                    String msg = d.get("message");
                    if (msg.isEmpty()) {
                        io.output("Usage: /send <message>");
                        return CommandResult.ALLOW;
                    }
                    if (ctx.currentAgent == null) {
                        ctx.currentAgent =
                                Agent.builder()
                                        .name("terminal-agent")
                                        .systemPrompt(
                                                "You are a helpful coding assistant. Be concise.")
                                        .sessionId(UUID.randomUUID().toString())
                                        .build()
                                        .withState(AgentState.RUNNING);
                        io.output("Session started (agent: " + ctx.currentAgent.id() + ")");
                    }
                    io.output("Thinking...");
                    VetoResponse r =
                            ctx.caller.call(
                                    new VetoRequest(
                                            ctx.currentAgent.systemPrompt()
                                                    + "\n\nRespond in JSON: {\"thought\":\"...\", \"call\":null, \"is_finished\":true}",
                                            msg,
                                            List.of(),
                                            ProviderType.DEEPSEEK,
                                            "deepseek-v4-pro",
                                            "deepseek-key",
                                            new LlmOptions(
                                                    0.0, null, 1024, Duration.ofSeconds(60))));
                    io.output("─".repeat(50));
                    io.output(r.thought());
                    io.output("─".repeat(50));
                    ctx.currentAgent =
                            ctx.currentAgent.appendTurn(
                                    new TurnRecord(
                                            ctx.currentAgent.nextTurnNumber(),
                                            r.thought(),
                                            null,
                                            null,
                                            null,
                                            null));
                    return CommandResult.ALLOW;
                },
                CommandArgument.of(DataConverter.DEFAULT_DATA_CONVERTER).named("message"));
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/send <message>");
    }
}
