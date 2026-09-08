package top.focess.veto.agent.tool.builtin;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.WorkspaceWriteCapability;
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
import top.focess.veto.agent.tool.WorkspaceWriteTool;

/** Moves one file, link, or bounded directory tree without overwrite or copy-delete fallback. */
@Component
@ToolSecurity(capability = ToolCapability.WORKSPACE_WRITE, defaultDanger = Danger.ELEVATED)
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description = "Move or rename one authorized file, link, or directory without overwriting.",
        behavior =
                "Moves the source to an existing destination parent without overwriting. A"
                        + " cross-filesystem move fails instead of copying and deleting the source."
                        + " Directory preflight does not follow links, snapshots entry identities,"
                        + " and is bounded to 50000 entries or 10 seconds.",
        whenToUse =
                "Use it to rename or relocate one exact file, symbolic link, or directory tree."
                        + " Discover an uncertain source first with find_files or list_dir.",
        whenNotToUse = "Do not use it to copy content or replace an existing destination.",
        resultContract =
                "Success returns JSON with `status`, the two requested paths as `source` and"
                        + " `destination`, and `kind` (`file`, `directory`, or `symbolic_link`). In"
                        + " detailed-result mode, failures use SOURCE_NOT_FOUND,"
                        + " DESTINATION_EXISTS, INVALID_DESTINATION, CROSS_FILESYSTEM_MOVE,"
                        + " MOVE_LIMIT_EXCEEDED, TREE_CHANGED, UNSAFE_LINK, or IO_ERROR; failure"
                        + " content is actionable plaintext in every mode.",
        errorsAndEdgeCases =
                "The destination parent must already exist. A destination created concurrently"
                        + " is not overwritten. Symbolic links are moved as links. Protected"
                        + " descendants reject a directory move before mutation. If an entry"
                        + " changes after preflight, the move stops with TREE_CHANGED before"
                        + " mutation.",
        security =
                "Both source and destination must be allowed by the current Boundaries rules. Moving files may require approval.",
        examples = {
            "{\"sourceAbsolutePath\":\"<workspace-root>/old.txt\",\"destinationAbsolutePath\":\"<workspace-root>/new.txt\"}"
        },
        returnExamples = {
            "{\"status\":\"moved\",\"source\":\"<workspace-root>/old.txt\",\"destination\":\"<workspace-root>/new.txt\",\"kind\":\"file\"}"
        })
public final class MovePathTool implements WorkspaceWriteTool<MovePathTool.Args> {

    public record Args(
            @NonNull @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute source path.")
                    String sourceAbsolutePath,
            @NonNull @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute destination path.")
                    String destinationAbsolutePath) {}

    @Override
    public @NonNull String getName() {
        return "move_path";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull WorkspaceWriteCapability workspace) {
        try {
            var source = workspace.file(args.sourceAbsolutePath());
            var destination = workspace.file(args.destinationAbsolutePath());
            String kind = source.kind();
            source.moveTo(destination);
            return ToolJson.object(
                    Map.of(
                            "status",
                            "moved",
                            "source",
                            args.sourceAbsolutePath(),
                            "destination",
                            args.destinationAbsolutePath(),
                            "kind",
                            kind));
        } catch (NoSuchFileException e) {
            return ToolErrors.failure(
                    "SOURCE_NOT_FOUND", "Source path not found: " + args.sourceAbsolutePath());
        } catch (FileAlreadyExistsException e) {
            return ToolErrors.failure(
                    "DESTINATION_EXISTS",
                    "Destination already exists: " + args.destinationAbsolutePath());
        } catch (IOException e) {
            return ToolErrors.failure("IO_ERROR", "Cannot move path: " + args.sourceAbsolutePath());
        }
    }
}
