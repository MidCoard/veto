package top.focess.veto.builtin.group;

import org.jspecify.annotations.NonNull;

/** Outcome of a requested DAG node mutation: either applied or rejected with a reason. */
public sealed interface NodeEdit {

    /** The edit was applied. */
    record Applied() implements NodeEdit {}

    /** The edit was rejected; {@code reason} explains why and what to do next. */
    record Rejected(@NonNull String reason) implements NodeEdit {}
}
