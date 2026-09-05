package top.focess.veto.agent.tool.builtin;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.WorkspaceReadCapability;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;
import top.focess.veto.agent.tool.WorkspaceReadTool;

/** Finds regular files through a call-scoped workspace-read capability. */
@Component
@ToolSecurity(capability = ToolCapability.WORKSPACE_READ, defaultDanger = Danger.SAFE)
public final class FindFilesTool implements WorkspaceReadTool<FindFilesTool.Args> {

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Find regular files below an authorized directory using a portable glob.",
            behavior =
                    "Searches recursively without following symbolic links or directory reparse"
                            + " points. The pattern uses `/` separators and supports `*`, `**`, and"
                            + " `?`. Matches are relative `/`-separated paths, sorted"
                            + " lexicographically; an empty `matches` array is a successful search with"
                            + " no matches. Returns at most 5000 matching file paths.",
            whenToUse = "Use it when you know a filename or portable glob but not its exact path.",
            whenNotToUse =
                    "Do not use it to search file contents; use grep_search. Do not use it when the"
                            + " exact path is already known.",
            resultContract =
                    "Returns JSON with `base`, `pattern`, `matches`, `truncated`,"
                            + " `truncationReason`, and `skippedEntries`. `truncationReason` is null or"
                            + " RESULT_LIMIT, VISIT_LIMIT, TIME_LIMIT, or OUTPUT_LIMIT. In"
                            + " detailed-result mode, failures use NOT_A_DIRECTORY, INVALID_PATTERN,"
                            + " PATH_PROTECTED, or IO_ERROR; failure content is actionable plaintext"
                            + " in every result mode.",
            errorsAndEdgeCases =
                    "Traversal also stops at 50000 visited entries, 1 MiB"
                            + " encoded output, or 10 seconds. `skippedEntries` counts unreadable,"
                            + " protected, symbolic-link, and reparse-point entries that were not"
                            + " traversed. `**/*.java` also matches a Java file directly below the"
                            + " base.",
            security =
                    "Read-only. Protected paths, symbolic links, and reparse points are skipped. Follow the current Boundaries rules.",
            examples = {
                "{\"absolutePath\":\"<workspace-root>\",\"pattern\":\"**/*.java\"}",
                "{\"absolutePath\":\"<workspace-root>\",\"pattern\":\"*.md\"}"
            },
            returnExamples = {
                "{\"base\":\"<workspace-root>\",\"pattern\":\"**/*.java\",\"matches\":[\"src/Main.java\"],\"truncated\":false,\"truncationReason\":null,\"skippedEntries\":0}"
            })
    public record Args(
            @NonNull
                    @SecurityHint(ParamCategory.FILESYSTEM_PATH)
                    @Doc("Absolute directory path to search below.")
                    String absolutePath,
            @NonNull @Doc("Portable relative-path glob using `*`, `**`, and `?`.")
                    String pattern) {}

    @Override
    public @NonNull String getName() {
        return "find_files";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull WorkspaceReadCapability capability) {
        return capability.findFiles("absolutePath", args.pattern());
    }
}
