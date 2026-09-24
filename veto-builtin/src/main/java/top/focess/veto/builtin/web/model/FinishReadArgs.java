package top.focess.veto.builtin.web.model;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;

public record FinishReadArgs(
        @Doc("complete, partial, or not_found, according to evidence and document coverage.")
                @NonNull String outcome,
        @Doc("Supported answer in the requested language, at most 4000 characters.")
                @NonNull String answer,
        @Doc("At most eight IDs of sections actually read; required for complete answers.")
                @NonNull List<@NonNull String> evidenceIds,
        @Doc(
                        "Concrete coverage or answer limitations; at most eight entries of 500 characters each.")
                @NonNull List<@NonNull String> limitations) {}
