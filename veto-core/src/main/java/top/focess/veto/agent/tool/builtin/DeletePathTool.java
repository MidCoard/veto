package top.focess.veto.agent.tool.builtin;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.WorkspaceWriteCapability;
import top.focess.veto.agent.capability.WritableWorkspaceFile;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.Required;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolJson;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;
import top.focess.veto.agent.tool.WorkspaceWriteTool;

/** Deletes one authorized path, with explicit recursive intent for non-empty directories. */
@Component
@ToolSecurity(
        capability = ToolCapability.WORKSPACE_WRITE,
        defaultDanger = Danger.DANGEROUS,
        requiresSemanticScreening = true)
public final class DeletePathTool implements WorkspaceWriteTool<DeletePathTool.Args> {
    private static final int MAX_ENTRIES = 50_000;
    private static final Duration MAX_DURATION = Duration.ofSeconds(10);

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description =
                    "Delete one authorized file, link, or directory with explicit recursive"
                            + " intent.",
            behavior =
                    "Deletes a file or link directly. An empty directory can be deleted with"
                            + " recursive=false; a non-empty directory requires recursive=true."
                            + " Recursive deletion performs a bounded no-follow identity snapshot and"
                            + " then deletes unchanged children before parents. It is not transactional"
                            + " and has no rollback.",
            whenToUse =
                    "Use it only when the exact requested path must be removed; inspect an"
                            + " uncertain target first with list_dir, find_files, or view_file.",
            whenNotToUse =
                    "Do not use it to clear generated output when a narrower build-tool cleanup is"
                            + " available. Do not use recursive=true speculatively.",
            resultContract =
                    "Success returns JSON with `status`, requested `path`, `kind` (`file`,"
                            + " `directory`, or `symbolic_link`), and `entriesDeleted`. In"
                            + " detailed-result mode, failures use PATH_NOT_FOUND, DIRECTORY_NOT_EMPTY,"
                            + " DELETE_LIMIT_EXCEEDED, SAFE_TREE_OPERATION_UNAVAILABLE, TREE_CHANGED,"
                            + " or IO_ERROR; protected paths are refused with PATH_PROTECTED or"
                            + " DESCENDANT_REFUSED. Failure content is actionable plaintext in every"
                            + " mode.",
            errorsAndEdgeCases =
                    "Links and Windows reparse points are deleted as links and never traversed."
                            + " Recursive preflight is limited to 50000 entries or 10 seconds. If the"
                            + " tree changes after preflight, deletion stops and reports TREE_CHANGED"
                            + " with the number already deleted in its message. Earlier deletions cannot"
                            + " be rolled back.",
            security =
                    "Deletion is destructive and may require approval. Verify the target and recursive flag before calling.",
            examples = {
                "{\"absolutePath\":\"<workspace-root>/obsolete.txt\",\"recursive\":false}",
                "{\"absolutePath\":\"<workspace-root>/generated\",\"recursive\":true}"
            },
            returnExamples = {
                "{\"status\":\"deleted\",\"path\":\"<workspace-root>/obsolete.txt\",\"kind\":\"file\",\"entriesDeleted\":1}"
            })
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
                                "DELETE_LIMIT_EXCEEDED",
                                "Directory preflight exceeded its safety limit.");
                    }
                }
            }
            for (int i = entries.size() - 1; i >= 0; i--) {
                entries.get(i).delete();
                deleted++;
            }
            return ToolJson.object(
                    Map.of(
                            "status",
                            "deleted",
                            "path",
                            args.absolutePath(),
                            "kind",
                            kind,
                            "entriesDeleted",
                            deleted));
        } catch (DirectoryNotEmptyException e) {
            if (args.recursive())
                return ToolErrors.failure(
                        "TREE_CHANGED",
                        "Directory changed during deletion after "
                                + deleted
                                + " entries were deleted.");
            return ToolErrors.failure(
                    "DIRECTORY_NOT_EMPTY", "Directory is not empty; recursive=true is required.");
        } catch (NoSuchFileException e) {
            return ToolErrors.failure(
                    deleted == 0 ? "PATH_NOT_FOUND" : "TREE_CHANGED",
                    "Path not found: " + args.absolutePath());
        } catch (IOException e) {
            return ToolErrors.failure(
                    "IO_ERROR",
                    "Deletion stopped after " + deleted + " entries: " + args.absolutePath());
        }
    }
}
