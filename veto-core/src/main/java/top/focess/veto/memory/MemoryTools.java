package top.focess.veto.memory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeMcpTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolSecurity;

/**
 * The agent-facing memory tools (long_term_memory_tiers.md §6). These are native tools, exactly
 * like {@code view_file} or {@code run_command}, so they pass through the Gateway (read tools are
 * {@code SAFE}; write tools are {@code ELEVATED} and audited). They are not host-path tools so they
 * carry no path-class danger; the screening model treats them per the generic/tool-declared option
 * set.
 */
public final class MemoryTools {

    private MemoryTools() {}

    /** {@code recall_session} — search captured chunks from the current Session LTM. */
    @Component
    @ToolSecurity(risk = RiskCategory.READ_ONLY)
    public static final class RecallSession implements NativeMcpTool<RecallSession.Args> {

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Free-text query to embed + search.")
                        String query,
                @SecurityHint(ParamCategory.GENERIC) @Doc("Optional top-K; defaults to 5.")
                        Integer topK,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Optional score floor in [0,1]; defaults to 0.5.")
                        Float scoreFloor) {}

        @Override
        public String getName() {
            return "recall_session";
        }

        @Override
        public String getDescription() {
            return "Search the current session's captured long-term memory (Session LTM). Returns up to top-K memories ranked by similarity, with source attribution.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            return ""; // tool body is invoked via the agent's MCP engine + memory tool wrapper
        }
    }

    /** {@code recall_insights} — search distilled insights from Cross-Session LTM. */
    @Component
    @ToolSecurity(risk = RiskCategory.READ_ONLY)
    public static final class RecallInsights implements NativeMcpTool<RecallInsights.Args> {

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Free-text query to embed + search.")
                        String query,
                @SecurityHint(ParamCategory.GENERIC) @Doc("Optional top-K; defaults to 5.")
                        Integer topK,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Optional score floor in [0,1]; defaults to 0.5.")
                        Float scoreFloor) {}

        @Override
        public String getName() {
            return "recall_insights";
        }

        @Override
        public String getDescription() {
            return "Search the user's distilled insights (Cross-Session LTM). Returns up to top-K memories ranked by similarity.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            return "";
        }
    }

    /**
     * {@code write_insight} — write a new insight to Cross-Session LTM, or promote a Session LTM
     * item.
     */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class WriteInsight implements NativeMcpTool<WriteInsight.Args> {

        public record Args(
                @SecurityHint(ParamCategory.CODE_CONTENT)
                        @Doc("The insight text to remember (already masked).")
                        String content,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Optional Session-LTM memory id to promote (curating boundary).")
                        String promoteMemoryId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Optional project id to tag the insight with.")
                        String projectId) {}

        @Override
        public String getName() {
            return "write_insight";
        }

        @Override
        public String getDescription() {
            return "Write a new insight to Cross-Session LTM, or promote a Session-LTM memory to Cross-Session LTM. Self-edit, audited.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            return "";
        }
    }

    /** {@code forget} — explicitly drop a memory. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class Forget implements NativeMcpTool<Forget.Args> {

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("The memory id to forget.")
                        String memoryId) {}

        @Override
        public String getName() {
            return "forget";
        }

        @Override
        public String getDescription() {
            return "Explicitly drop a memory (user- or agent-initiated). Self-edit, audited.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            return "";
        }
    }

    /**
     * Helper to format scored-memory results for a tool observation (DATA — not instructions
     * framing is applied at ingress via {@code IngressDefense}).
     */
    public static String formatResults(List<MemoryStore.ScoredMemory> results) {
        if (results == null || results.isEmpty()) {
            return "no matching memories";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(results.size()).append(" memories:\n");
        for (MemoryStore.ScoredMemory sm : results) {
            Memory m = sm.memory();
            sb.append("- [")
                    .append(m.tier())
                    .append("] id=")
                    .append(m.id().value())
                    .append(" score=")
                    .append(String.format("%.3f", sm.score()))
                    .append(" src=")
                    .append(m.sourceRef().kind())
                    .append(" ")
                    .append(m.sourceRef().attrs())
                    .append("\n");
            String content = m.content();
            if (content != null && content.length() > 240) {
                content = content.substring(0, 240) + "...";
            }
            sb.append("  ").append(content).append("\n");
        }
        return sb.toString();
    }

    /** Helper to extract the userId from a context map (the agent's per-session user). */
    public static UUID userIdFromContext(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object v = context.get("userId");
        return v instanceof UUID u ? u : null;
    }
}
