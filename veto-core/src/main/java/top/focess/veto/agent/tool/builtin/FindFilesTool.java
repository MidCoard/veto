package top.focess.veto.agent.tool.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.WorkspaceFile;
import top.focess.veto.agent.capability.WorkspaceReadCapability;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrorCode;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolJson;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;
import top.focess.veto.agent.tool.WorkspaceReadTool;

/** Finds regular files through a call-scoped workspace-read capability. */
@Component
@ToolSecurity(capability = ToolCapability.WORKSPACE_READ, defaultDanger = Danger.SAFE)
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description = "Find regular files below an authorized directory using a portable glob.",
        behavior =
                """
                Searches recursively without following symbolic links or directory reparse points. \
                The pattern uses `/` separators and supports `*`, `**`, and `?`. Matches are \
                relative `/`-separated paths, sorted lexicographically; an empty `matches` array is \
                a successful search with no matches. Returns at most 5000 matching file paths.""",
        whenToUse = "Use it when you know a filename or portable glob but not its exact path.",
        whenNotToUse =
                """
                Do not use it to search file contents; use grep_search. Do not use it when the \
                exact path is already known.""",
        resultContract =
                """
                Returns JSON with `base`, `pattern`, `matches`, `truncated`, `truncationReason`, \
                and `skippedEntries`. `truncationReason` is null or RESULT_LIMIT, VISIT_LIMIT, \
                TIME_LIMIT, or OUTPUT_LIMIT. In detailed-result mode, failures use NOT_A_DIRECTORY \
                (`Not a directory: <absolutePath>`), INVALID_ARGUMENTS \
                (`Invalid arguments: pattern must be non-blank and use '/' separators.`), or \
                IO_ERROR (`I/O error: cannot search directory <absolutePath>.`); protected roots \
                are refused with PATH_PROTECTED. Failure content is actionable plaintext in every \
                result mode.""",
        errorsAndEdgeCases =
                """
                Traversal also stops at 50000 visited entries, 1 MiB encoded output, or 10 seconds. \
                `skippedEntries` counts unreadable, protected, symbolic-link, and reparse-point \
                entries that were not traversed. `**/*.java` also matches a Java file directly \
                below the base.""",
        security =
                "Protected paths, symbolic links, and reparse points are skipped without being opened; they are counted in `skippedEntries`.",
        examples = {
            "{\"absolutePath\":\"/abs/project\",\"pattern\":\"**/*.java\"}",
            "{\"absolutePath\":\"/abs/project\",\"pattern\":\"*.md\"}",
            "{\"absolutePath\":\"/abs/project\",\"pattern\":\"**/build.gradle.kts\"}",
            "{\"absolutePath\":\"/abs/project/src\",\"pattern\":\"**/test_?.py\"}",
            "{\"absolutePath\":\"/abs/project/notes.txt\",\"pattern\":\"**/*.txt\"}"
        },
        returnExamples = {
            "{\"base\":\"/abs/project\",\"pattern\":\"**/*.java\",\"matches\":[\"src/Main.java\",\"src/util/Helper.java\"],\"truncated\":false,\"truncationReason\":null,\"skippedEntries\":0}",
            "{\"base\":\"/abs/project\",\"pattern\":\"*.md\",\"matches\":[\"README.md\"],\"truncated\":false,\"truncationReason\":null,\"skippedEntries\":0}",
            "{\"base\":\"/abs/project\",\"pattern\":\"**/build.gradle.kts\",\"matches\":[\"build.gradle.kts\",\"veto-core/build.gradle.kts\"],\"truncated\":false,\"truncationReason\":null,\"skippedEntries\":0}",
            "{\"base\":\"/abs/project/src\",\"pattern\":\"**/test_?.py\",\"matches\":[],\"truncated\":false,\"truncationReason\":null,\"skippedEntries\":0}",
            "Not a directory: /abs/project/notes.txt"
        })
public final class FindFilesTool implements WorkspaceReadTool<FindFilesTool.Args> {

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
    public @NonNull String execute(@NonNull Args args, @NonNull WorkspaceReadCapability workspace) {
        if (args.pattern().isBlank() || args.pattern().indexOf('\\') >= 0) {
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: pattern must be non-blank and use '/' separators.");
        }
        Pattern matcher = Pattern.compile(globRegex(args.pattern()));
        try {
            WorkspaceFile root = workspace.file(args.absolutePath());
            if (!root.kind().equals("directory")) {
                return ToolErrors.failure(
                        ToolErrorCode.WORKSPACE.NOT_A_DIRECTORY,
                        "Not a directory: " + args.absolutePath());
            }
            var traversal = new WorkspaceTraversal(root);
            List<String> matches = new ArrayList<>();
            String reason = null;
            while (traversal.next() != null) {
                String relative = traversal.relativeName();
                if (matcher.matcher(relative).matches()) {
                    if (matches.size() == 5000) {
                        reason = "RESULT_LIMIT";
                        break;
                    }
                    matches.add(relative);
                }
            }
            if (reason == null) reason = traversal.reason();
            matches.sort(Comparator.naturalOrder());
            while (true) {
                String json =
                        ToolJson.object(
                                new Result(
                                        args.absolutePath(),
                                        args.pattern(),
                                        List.copyOf(matches),
                                        reason != null,
                                        reason,
                                        traversal.skipped()));
                if (json.getBytes(StandardCharsets.UTF_8).length <= 1024 * 1024
                        || matches.isEmpty()) {
                    return json;
                }
                matches.removeLast();
                reason = "OUTPUT_LIMIT";
            }
        } catch (NoSuchFileException e) {
            return ToolErrors.failure(
                    ToolErrorCode.WORKSPACE.NOT_A_DIRECTORY,
                    "Not a directory: " + args.absolutePath());
        } catch (IOException e) {
            return ToolErrors.failure(
                    ToolErrorCode.WORKSPACE.IO_ERROR,
                    "I/O error: cannot search directory " + args.absolutePath() + ".");
        }
    }

    private static @NonNull String globRegex(@NonNull String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char current = glob.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
                if (doubleStar) {
                    index++;
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '/') {
                        index++;
                        regex.append("(?:.*/)?");
                    } else regex.append(".*");
                } else regex.append("[^/]*");
            } else if (current == '?') {
                regex.append("[^/]");
            } else {
                if (".[]{}()+-^$|\\".indexOf(current) >= 0) regex.append('\\');
                regex.append(current);
            }
        }
        return regex.append('$').toString();
    }

    public record Result(
            @NonNull String base,
            @NonNull String pattern,
            @NonNull List<String> matches,
            boolean truncated,
            @org.jspecify.annotations.Nullable String truncationReason,
            int skippedEntries) {}
}
