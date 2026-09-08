package top.focess.veto.agent.tool.builtin;

import com.fasterxml.jackson.databind.node.NullNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                    "INVALID_PATTERN", "Pattern must be non-blank and use '/' separators.");
        }
        Pattern matcher = Pattern.compile(globRegex(args.pattern()));
        try {
            WorkspaceFile root = workspace.file(args.absolutePath());
            if (!root.kind().equals("directory")) {
                return ToolErrors.failure(
                        "NOT_A_DIRECTORY", "Not a directory: " + args.absolutePath());
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
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("base", args.absolutePath());
                result.put("pattern", args.pattern());
                result.put("matches", List.copyOf(matches));
                result.put("truncated", reason != null);
                result.put("truncationReason", reason == null ? NullNode.getInstance() : reason);
                result.put("skippedEntries", traversal.skipped());
                String json = ToolJson.object(result);
                if (json.getBytes(StandardCharsets.UTF_8).length <= 1024 * 1024
                        || matches.isEmpty()) {
                    return json;
                }
                matches.removeLast();
                reason = "OUTPUT_LIMIT";
            }
        } catch (NoSuchFileException e) {
            return ToolErrors.failure("NOT_A_DIRECTORY", "Not a directory: " + args.absolutePath());
        } catch (IOException e) {
            return ToolErrors.failure(
                    "IO_ERROR", "Cannot search directory: " + args.absolutePath());
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
}
