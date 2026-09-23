package top.focess.veto.api.group;

import java.time.*;
import java.util.*;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.*;

public sealed interface NodeEdit {

    /** The edit was applied. */
    record Applied() implements NodeEdit {}

    /** The edit was rejected; {@code reason} explains why and what to do next. */
    record Rejected(@NonNull String reason) implements NodeEdit {}
}
