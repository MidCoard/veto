package top.focess.veto.command.commands;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import top.focess.veto.agent.Agent;
import top.focess.veto.command.ArgDef;
import top.focess.veto.command.CommandHandler;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;

public class TurnsCommand implements CommandHandler {

    private final ConcurrentHashMap<String, Agent> sessions;

    public TurnsCommand(ConcurrentHashMap<String, Agent> sessions) {
        this.sessions = sessions;
    }

    @Override
    public String name() {
        return "turns";
    }

    @Override
    public String description() {
        return "Show turn history for current session";
    }

    @Override
    public String usage() {
        return "turns";
    }

    @Override
    public List<ArgDef> arguments() {
        return List.of();
    }

    @Override
    public TerminalResponse execute(Map<String, Object> args, String sessionToken) {
        if (sessionToken == null)
            return TerminalResponse.error("No active session. Send a message first: send <msg>");

        Agent agent = sessions.get(sessionToken);
        if (agent == null || agent.turns().isEmpty())
            return new TerminalResponse(ResponseType.MESSAGE, "No turns yet.");

        StringBuilder sb = new StringBuilder();
        agent.turns().stream()
                .sorted(Comparator.comparingInt(t -> t.turnNumber()))
                .forEach(
                        t -> {
                            String thought = t.thought();
                            if (thought != null && thought.length() > 200)
                                thought = thought.substring(0, 200) + "...";
                            sb.append("#")
                                    .append(t.turnNumber())
                                    .append(": ")
                                    .append(thought)
                                    .append("\n");
                        });
        return new TerminalResponse(ResponseType.MESSAGE, sb.toString());
    }
}
