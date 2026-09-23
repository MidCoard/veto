package top.focess.veto.agent.tool.builtin;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.WorkspaceWriteCapability;
import top.focess.veto.agent.capability.WritableWorkspaceFile;
import top.focess.veto.agent.tool.ToolErrorCode;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolJson;
import top.focess.veto.agent.tool.WorkspaceWriteTool;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.Required;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;

/** Deletes one authorized path, with explicit recursive intent for non-empty directories. */
@Component
@ToolSecurity(
        capability = ToolCapability.WORKSPACE_WRITE,
        defaultDanger = Danger.DANGEROUS,
        requiresSemanticScreening = true)
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description =
                "Delete one authorized file, link, or directory with explicit recursive intent.",
        behavior =
                """
                Deletes a file or link directly. An empty directory can be deleted with \
                recursive=false; a non-empty directory requires recursive=true. Recursive deletion \
                performs a bounded no-follow identity snapshot and then deletes unchanged children \
                before parents. It is not transactional and has no rollback.""",
        whenToUse =
                """
                Use it only when the exact requested path must be removed; inspect an uncertain \
                target first with list_dir, find_files, or view_file.""",
        whenNotToUse =
                """
                Do not use it to clear generated output when a narrower build-tool cleanup is \
                available. Do not use recursive=true speculatively.""",
        resultContract =
                """
                Success returns JSON with `status`, requested `path`, `kind` (`file`, `directory`, \
                or `symbolic_link`), and `entriesDeleted`. In detailed-result mode, failures use \
                PATH_NOT_FOUND (`Path not found: <absolutePath>`), DIRECTORY_NOT_EMPTY \
                (`Directory not empty: the directory is not empty; recursive=true is required.`), \
                DELETE_LIMIT_EXCEEDED \
                (`Delete limit exceeded: directory preflight exceeded its safety limit.`), \
                TREE_CHANGED (`Tree changed: the directory changed during deletion after <n> \
                entries were deleted.`), or IO_ERROR \
                (`I/O error: deletion stopped after <n> entries while deleting <absolutePath>.`); \
                protected paths are refused with PATH_PROTECTED or DESCENDANT_REFUSED. Failure \
                content is actionable plaintext in every mode.""",
        errorsAndEdgeCases =
                """
                Links and Windows reparse points are deleted as links and never traversed. \
                Recursive preflight is limited to 50000 entries or 10 seconds. If the tree changes \
                after preflight, deletion stops and reports TREE_CHANGED with the number already \
                deleted in its message. Earlier deletions cannot be rolled back.""",
        security =
                "Deletion is irreversible and not transactional; entries already deleted cannot be rolled back. Verify the target and recursive flag before calling.",
        examples = {
            "{\"absolutePath\":\"/abs/project/obsolete.txt\",\"recursive\":false}",
            "{\"absolutePath\":\"/abs/project/build/empty-out\",\"recursive\":false}",
            "{\"absolutePath\":\"/abs/project/out/current-link\",\"recursive\":false}",
            "{\"absolutePath\":\"/abs/project/generated\",\"recursive\":true}",
            "{\"absolutePath\":\"/abs/project/no-such-path.txt\",\"recursive\":false}"
        },
        returnExamples = {
            "{\"status\":\"deleted\",\"path\":\"/abs/project/obsolete.txt\",\"kind\":\"file\",\"entriesDeleted\":1}",
            "{\"status\":\"deleted\",\"path\":\"/abs/project/build/empty-out\",\"kind\":\"directory\",\"entriesDeleted\":1}",
            "{\"status\":\"deleted\",\"path\":\"/abs/project/out/current-link\",\"kind\":\"symbolic_link\",\"entriesDeleted\":1}",
            "{\"status\":\"deleted\",\"path\":\"/abs/project/generated\",\"kind\":\"directory\",\"entriesDeleted\":42}",
            "Path not found: /abs/project/no-such-path.txt"
        })
public final class DeletePathTool implements WorkspaceWriteTool<DeletePathTool.Args> {
    private static final int MAX_ENTRIES = 50_000;
    private static final @NonNull Duration MAX_DURATION = Duration.ofSeconds(10);

    public record Args(
            @NonNull @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path to delete.")
                    String absolutePath,
            @Required @Doc("Whether deletion may recurse through a non-empty directory.")
                    boolean recursive) {}

    @Override
    public @NonNull String getName() {
        return "delete_path";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull WorkspaceWriteCapability workspace) {
        int deleted = 0;
        try {
            var root = workspace.file(args.absolutePath());
            String kind = root.kind();
            List<@NonNull WritableWorkspaceFile> entries = new ArrayList<>();
            entries.add(root);
            if (args.recursive() && "directory".equals(kind)) {
                Instant started = Instant.now();
                for (int i = 0; i < entries.size(); i++) {
                    var entry = entries.get(i);
                    if ("directory".equals(entry.kind())) entries.addAll(entry.children());
                    if (entries.size() > MAX_ENTRIES
                            || Duration.between(started, Instant.now()).compareTo(MAX_DURATION)
                                    > 0) {
                        return ToolErrors.failure(
                                ToolErrorCode.WORKSPACE.DELETE_LIMIT_EXCEEDED,
                                "Delete limit exceeded: directory preflight exceeded its safety"
                                        + " limit.");
                    }
                }
            }
            for (int i = entries.size() - 1; i >= 0; i--) {
                entries.get(i).delete();
                deleted++;
            }
            return ToolJson.object(new Result("deleted", args.absolutePath(), kind, deleted));
        } catch (DirectoryNotEmptyException e) {
            if (args.recursive())
                return ToolErrors.failure(
                        ToolErrorCode.WORKSPACE.TREE_CHANGED,
                        "Tree changed: the directory changed during deletion after "
                                + deleted
                                + " entries were deleted.");
            return ToolErrors.failure(
                    ToolErrorCode.WORKSPACE.DIRECTORY_NOT_EMPTY,
                    "Directory not empty: the directory is not empty; recursive=true is required.");
        } catch (NoSuchFileException e) {
            if (deleted == 0) {
                return ToolErrors.failure(
                        ToolErrorCode.WORKSPACE.PATH_NOT_FOUND,
                        "Path not found: " + args.absolutePath());
            }
            return ToolErrors.failure(
                    ToolErrorCode.WORKSPACE.TREE_CHANGED,
                    "Tree changed: "
                            + args.absolutePath()
                            + " disappeared after "
                            + deleted
                            + " entries were deleted.");
        } catch (IOException e) {
            return ToolErrors.failure(
                    ToolErrorCode.WORKSPACE.IO_ERROR,
                    "I/O error: deletion stopped after "
                            + deleted
                            + " entries while deleting "
                            + args.absolutePath()
                            + ".");
        }
    }

    public record Result(
            @NonNull String status,
            @NonNull String path,
            @NonNull String kind,
            int entriesDeleted) {}
}
