package top.focess.veto.command;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TerminalSessionManager {

    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    public String create(String username) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, username);
        return token;
    }

    public String resolve(String token) {
        return token != null ? sessions.get(token) : null;
    }

    public void invalidate(String token) {
        sessions.remove(token);
    }

    public void invalidateAll(String username) {
        sessions.values().removeIf(username::equals);
    }
}
