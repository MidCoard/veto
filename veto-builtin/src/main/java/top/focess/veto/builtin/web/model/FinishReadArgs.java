package top.focess.veto.builtin.web.model;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ArraySize;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.StringConstraint;

/** Model-facing arguments of the reader-internal {@code finish_read} tool. */
public record FinishReadArgs(
        @StringConstraint(pattern = "^(complete|partial|not_found)$")
                @Doc(
                        "complete, partial, or not_found, according to evidence and document coverage.")
                @NonNull String outcome,
        @StringConstraint(maxLength = 4000, rejectBlank = true)
                @Doc("Supported answer in the requested language, at most 4000 characters.")
                @NonNull String answer,
        @ArraySize(min = 0, max = 8)
                @Doc("At most eight IDs of sections actually read; required for complete answers.")
                @NonNull List<@NonNull String> evidenceIds,
        @ArraySize(min = 0, max = 8)
                @Doc(
                        "Concrete coverage or answer limitations; at most eight entries of 500 characters each.")
                @NonNull List<@NonNull @StringConstraint(maxLength = 500) String> limitations) {}
