package top.focess.veto.agent.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.skills.Skill;

/**
 * JPA persistence for {@link AgentPersona} (Part 5 persona DB persistence). The persona's skills +
 * whitelisted tools are persisted as comma-separated values; the production wiring would resolve
 * tool names → {@link ToolDefinition} and skill names → {@link Skill} at load time.
 */
@Entity
@Table(name = "personas")
public class PersonaEntity {

    @Id private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "whitelisted_tools", columnDefinition = "TEXT")
    private String whitelistedTools;

    @Column(name = "skills", columnDefinition = "TEXT")
    private String skills;

    @Column(name = "top_model")
    private String topModel;

    @Column(name = "mid_model")
    private String midModel;

    @Column(name = "low_model")
    private String lowModel;

    protected PersonaEntity() {}

    public
    @NonNull
    PersonaEntity(@NonNull AgentPersona persona) {
        this.id = persona.id();
        this.name = persona.name();
        this.description = persona.description();
        this.whitelistedTools =
                String.join(
                        ",",
                        persona.whitelistedTools().stream().map(ToolDefinition::name).toList());
        this.skills =
                String.join(",", persona.registeredSkills().stream().map(Skill::name).toList());
        this.topModel = persona.topModel();
        this.midModel = persona.midModel();
        this.lowModel = persona.lowModel();
    }

    public static @NonNull AgentPersona toPersona(@NonNull PersonaEntity e) {
        return new AgentPersona(
                e.id,
                e.name,
                e.description == null ? "" : e.description,
                java.util.Set.of(), // tool resolution is a wiring concern
                parseSkillList(e.skills),
                e.topModel == null ? "gemini-3.5-flash" : e.topModel,
                e.midModel,
                e.lowModel,
                Role.STANDALONE);
    }

    private static java.util.List<Skill> parseSkillList(String s) {
        if (s == null || s.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<Skill> out = new java.util.ArrayList<>();
        for (String name : s.split(",")) {
            out.add(new Skill(name, "", "", null, null, java.util.List.of(), ""));
        }
        return out;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getWhitelistedTools() {
        return whitelistedTools;
    }

    public String getSkills() {
        return skills;
    }
}
