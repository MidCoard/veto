package top.focess.veto.agent.tool.builtin;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.WorkspaceWriteCapability;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.Required;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
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
                    "Workspace write tool with a deterministic DANGEROUS floor, mandatory semantic"
                            + " screening, human approval when required, and a call-scoped capability"
                            + " issued by the Gateway.",
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
    public @NonNull String getDescription() {
        return "Delete one file, link, or directory with explicit recursive intent.";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull WorkspaceWriteCapability capability) {
        return capability.deletePath("absolutePath", args.recursive());
    }
}
