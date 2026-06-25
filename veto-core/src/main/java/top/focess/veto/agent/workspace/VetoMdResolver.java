package top.focess.veto.agent.workspace;

import java.util.List;

/** Resolves VETO.md (The Law) per-root + cross-root. Real logic in Task 4. */
public record VetoMdResolver(List<WorkspaceRoot> roots) {
    public String resolve() {
        return "";
    }
}
