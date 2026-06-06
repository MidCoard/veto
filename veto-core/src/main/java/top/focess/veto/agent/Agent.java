package top.focess.veto.agent;

import java.time.Instant;
import java.util.*;

/**
 * A Veto agent — the central entity in the agent loop (ARCHITECTURE.md Part 5). Holds identity
 * (persona), state, tool arsenal, and turn history.
 *
 * <p>Immutable. State transitions return new instances via {@code withState()} and {@code
 * appendTurn()}.
 */
public class Agent {

    private final String id;
    private final String name;
    private final String description;
    private final String systemPrompt;
    private final AgentState state;
    private final Set<String> toolNames;
    private final List<TurnRecord> turns;
    private final Instant createdAt;
    private final String sessionId;

    private Agent(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(builder.name, "name");
        this.description = builder.description != null ? builder.description : "";
        this.systemPrompt = Objects.requireNonNull(builder.systemPrompt, "systemPrompt");
        this.state = builder.state;
        this.toolNames = Set.copyOf(builder.toolNames);
        this.turns = List.copyOf(builder.turns);
        this.createdAt = builder.createdAt;
        this.sessionId = builder.sessionId;
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public AgentState state() {
        return state;
    }

    public Set<String> toolNames() {
        return toolNames;
    }

    public List<TurnRecord> turns() {
        return turns;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String sessionId() {
        return sessionId;
    }

    // ── State transitions ───────────────────────────────────────────────────

    public Agent withState(AgentState newState) {
        return new Builder(this).state(newState).build();
    }

    /** Records a completed turn and appends it to the turn history. */
    public Agent appendTurn(TurnRecord turn) {
        List<TurnRecord> newTurns = new ArrayList<>(this.turns);
        newTurns.add(turn);
        return new Builder(this).turns(newTurns).build();
    }

    public Agent withToolNames(Set<String> toolNames) {
        return new Builder(this).toolNames(new HashSet<>(toolNames)).build();
    }

    public int nextTurnNumber() {
        return turns.size() + 1;
    }

    // ── Builder ─────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String name;
        private String description = "";
        private String systemPrompt;
        private AgentState state = AgentState.IDLE;
        private Set<String> toolNames = Set.of();
        private List<TurnRecord> turns = List.of();
        private Instant createdAt = Instant.now();
        private String sessionId;

        public Builder() {}

        Builder(Agent agent) {
            this.id = agent.id;
            this.name = agent.name;
            this.description = agent.description;
            this.systemPrompt = agent.systemPrompt;
            this.state = agent.state;
            this.toolNames = new HashSet<>(agent.toolNames);
            this.turns = new ArrayList<>(agent.turns);
            this.createdAt = agent.createdAt;
            this.sessionId = agent.sessionId;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder systemPrompt(String sp) {
            this.systemPrompt = sp;
            return this;
        }

        public Builder state(AgentState state) {
            this.state = state;
            return this;
        }

        public Builder toolNames(Set<String> names) {
            this.toolNames = names;
            return this;
        }

        public Builder turns(List<TurnRecord> turns) {
            this.turns = turns;
            return this;
        }

        public Builder createdAt(Instant at) {
            this.createdAt = at;
            return this;
        }

        public Builder sessionId(String sid) {
            this.sessionId = sid;
            return this;
        }

        public Agent build() {
            return new Agent(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Agent agent)) return false;
        return id.equals(agent.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Agent{id='" + id + "', name='" + name + "', state=" + state + "}";
    }
}
