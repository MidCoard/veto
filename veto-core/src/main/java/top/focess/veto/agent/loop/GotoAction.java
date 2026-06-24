package top.focess.veto.agent.loop;

/** Unconditional jump ( {@code goto}). Zero model calls. */
public record GotoAction(String id, String label, int index) implements Action {}
