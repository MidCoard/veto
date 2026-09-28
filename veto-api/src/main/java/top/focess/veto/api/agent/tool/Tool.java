package top.focess.veto.api.agent.tool;

/** Base class for every object registered at the tools contribution point. */
public abstract class Tool {
    /** Constructs a tool; the host binds it to the contributing plugin's lifecycle. */
    protected Tool() {}
}
