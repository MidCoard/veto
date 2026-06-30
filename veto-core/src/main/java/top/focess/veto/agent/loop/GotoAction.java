package top.focess.veto.agent.loop;

import org.jspecify.annotations.NonNull;

/** Unconditional jump ( {@code goto}). Zero model calls. */
public record GotoAction(@NonNull String id, @NonNull String label, int index) implements Action {}
