package top.focess.veto.builtin.response;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.contract.ModelResponsePolicy;

/** Cited-answer validation and bounded repair policy belong to the feature. */
public final class CitationResponsePolicy implements ModelResponsePolicy {
    public @NonNull Exchange open() {
        return new Exchange() {
            private int repairs;
            private @Nullable Result candidate;

            public @NonNull Result check(
                    @NonNull VetoResponse response, @NonNull SourceEvidence evidence) {
                ResponseEnforcer.enforce(response);
                var declarations = response.citations();
                if (declarations == null || declarations.isEmpty())
                    return new Result(response, null, null);
                var inspected = evidence.inspectResolved(declarations);
                var result = new Result(response, inspected.receipt(), null);
                var missing =
                        inspected.issues().stream()
                                .filter(issue -> issue.status().equals("not_found"))
                                .findFirst();
                if (missing.isPresent() && repairs < 2) {
                    candidate = result;
                    repairs++;
                    var issue = missing.get();
                    var messages = evidence.messages();
                    var role =
                            messages.stream()
                                    .filter(message -> message.index() == issue.messageIndex())
                                    .map(SourceEvidence.Message::role)
                                    .findFirst()
                                    .orElse("");
                    return new Result(
                            response,
                            inspected.receipt(),
                            new Correction(
                                    "builtin-citation-correction",
                                    Map.of(
                                            "id",
                                            issue.id(),
                                            "index",
                                            issue.messageIndex(),
                                            "selected",
                                            role,
                                            "count",
                                            messages.size(),
                                            "items",
                                            messages.stream()
                                                    .skip(Math.max(0, messages.size() - 64))
                                                    .toList())));
                }
                return result;
            }

            public @Nullable Result rejected(int failures) {
                return failures >= 2 ? candidate : null;
            }
        };
    }
}
