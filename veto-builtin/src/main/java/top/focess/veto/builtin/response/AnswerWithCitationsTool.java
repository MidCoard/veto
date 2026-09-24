package top.focess.veto.builtin.response;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.ResponseCapability;
import top.focess.veto.api.agent.response.ResponseRequest;
import top.focess.veto.api.agent.tool.*;
import top.focess.veto.api.agent.tool.ArraySize;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ResponseSubmission;
import top.focess.veto.api.agent.tool.ResponseTool;
import top.focess.veto.api.agent.tool.StringConstraint;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolResultFormat;

@ResponseSubmission(ResponseSubmission.Kind.ANSWER)
@ToolDoc(
        description =
                "Use only for answers that need verified, clickable citations to conversation text or tool results. Ordinary answers use plain text, without this tool. Supply message with [label](cite:id) links and a nonempty citations array of exact source quotes. Call this tool alone; it publishes the answer.",
        behavior =
                "Locates each exact quote in the visible conversation and verifies its source and returns the answer with verified source metadata. In a conversation this publishes the answer and finishes the turn; in plan generation it becomes the step output. Call this tool alone. Its message is the answer; no extra final text is needed after success.",
        whenToUse =
                "Use when the user requests clickable conversation sources or the answer attributes an exact passage to a prior message or tool result. The current user message is also a valid source. String values inside JSON tool results can be quoted.",
        whenNotToUse =
                "Reply directly in text for answers without verified conversation references. Ordinary external URLs do not require this tool. A plain blockquote or a handwritten citation marker does not create source metadata.",
        resultContract =
                "Success: JSON {\"status\":\"accepted\"}; the submitted answer is published with verified source links. Failure (INVALID_CITATION) returns `Citation rejected: <detail>` as plaintext, publishes no answer, and keeps the conversation active so you can correct the source reference or answer without a citation.",
        errorsAndEdgeCases =
                "Use [label](cite:id) links in message and declare each id once; every declaration must have a link and every link must have a declaration. Both citations and each sources value are nonempty arrays, even for one item. Omit message_index normally: do not count messages. Identical complete results from the same tool and arguments retain all repeated occurrences as sources. Otherwise, use a longer unique quote or select a message_index from the returned candidates. Copy punctuation and whitespace verbatim. Each citation supports 1-8 passages, each up to 4000 characters; at most 32 citations. References attach to this answer; no memory write or file creation is needed.",
        security =
                "References are limited to the calling agent's current visible input. This tool cannot retrieve other sessions or access files. A matched quote establishes its source, not the truth of its claim.",
        resultFormats = {ToolResultFormat.JSON},
        examples = {
            "{\"message\":\"The meeting starts at [14:30](cite:meeting).\",\"citations\":[{\"id\":\"meeting\",\"sources\":[{\"quote\":\"The meeting starts at 14:30.\"}]}]}",
            "{\"message\":\"The build uses [Gradle 8.5](cite:gradle) and targets [Java 21](cite:java).\",\"citations\":[{\"id\":\"gradle\",\"sources\":[{\"quote\":\"The build uses Gradle 8.5\"}]},{\"id\":\"java\",\"sources\":[{\"quote\":\"and targets Java 21\"}]}]}",
            "{\"message\":\"Both reviewers approved the change: [the approvals](cite:approvals).\",\"citations\":[{\"id\":\"approvals\",\"sources\":[{\"quote\":\"Alice approved the pull request.\"},{\"quote\":\"Bob approved the pull request.\"}]}]}",
            "{\"message\":\"The configured timeout is [30 seconds](cite:timeout).\",\"citations\":[{\"id\":\"timeout\",\"sources\":[{\"message_index\":7,\"quote\":\"\\\"timeout\\\": \\\"30 seconds\\\"\"}]}]}",
            "{\"message\":\"The deadline is [next Friday](cite:deadline).\",\"citations\":[{\"id\":\"deadline\",\"sources\":[{\"quote\":\"The deadline is next Friday.\"}]}]}"
        },
        returnExamples = {
            "{\"status\":\"accepted\"}",
            "{\"status\":\"accepted\"}",
            "{\"status\":\"accepted\"}",
            "{\"status\":\"accepted\"}",
            "Citation rejected: Citation deadline: quote was not found in visible conversation evidence. Copy a longer exact passage from the source; do not paraphrase or invent a message index."
        })
public final class AnswerWithCitationsTool implements ResponseTool<AnswerWithCitationsTool.Args> {
    private final ResponseCapability capability;

    public AnswerWithCitationsTool() {
        this.capability = null;
    }

    public AnswerWithCitationsTool(@NonNull ResponseCapability capability) {
        this.capability = capability;
    }

    @Override
    public @NonNull String getName() {
        return "answer_with_citations";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull ResponseCapability responseCapability() {
        if (capability == null) throw new SecurityException("Host must supply loop control");
        if (capability == null) throw new SecurityException("Host must supply tool capability");
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull ResponseCapability capability)
            throws Exception {
        var citations =
                args.citations().stream()
                        .map(
                                c ->
                                        new ResponseRequest.Citation(
                                                c.id(),
                                                c.sources().stream()
                                                        .map(
                                                                s ->
                                                                        new ResponseRequest.Source(
                                                                                s.message_index(),
                                                                                s.quote()))
                                                        .toList()))
                        .toList();
        capability.answerWithCitations(new ResponseRequest.Answer(args.message(), citations));
        return "{\"status\":\"accepted\"}";
    }

    public record Args(
            @NonNull
                    @StringConstraint(minLength = 1)
                    @Doc(
                            "Complete final answer, with [label](cite:id) links matching the citation declarations. Published only after validation.")
                    String message,
            @NonNull
                    @ArraySize(min = 1, max = 32)
                    @Doc(
                            "Nonempty array of declarations for every cite: link. Do not call this tool with [] or for an answer without citation links; reply in plain text instead.")
                    List<@NonNull Citation> citations) {}

    public record Citation(
            @NonNull
                    @StringConstraint(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")
                    @Doc("Unique link identifier matching cite:id in message.")
                    String id,
            @NonNull
                    @ArraySize(min = 1, max = 8)
                    @Doc(
                            "Nonempty array of exact passages supporting this citation, even when there is only one passage.")
                    List<@NonNull Source> sources) {}

    public record Source(
            @Doc(
                            "Optional disambiguation index supplied by a tool error when a quote has multiple sources. Omit normally; never guess or count messages.")
                    Integer message_index,
            @NonNull
                    @StringConstraint(minLength = 1, maxLength = 4000)
                    @Doc(
                            "Exact contiguous text copied from that message. JSON string values may be quoted. Preserve punctuation and whitespace.")
                    String quote) {}
}
