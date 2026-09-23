package top.focess.veto.builtin.workspace;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.WorkspaceWriteCapability;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolJson;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.api.agent.tool.WorkspaceWriteTool;

/** Moves one file, link, or bounded directory tree without overwrite or copy-delete fallback. */
@ToolSecurity(capability = ToolCapability.WORKSPACE_WRITE, defaultDanger = Danger.ELEVATED)
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description = "Move or rename one authorized file, link, or directory without overwriting.",
        behavior =
                """
                Moves the source to an existing destination parent without overwriting. A \
                cross-filesystem move fails instead of copying and deleting the source. Directory \
                preflight does not follow links, snapshots entry identities, and is bounded to \
                50000 entries or 10 seconds.""",
        whenToUse =
                """
                Use it to rename or relocate one exact file, symbolic link, or directory tree. \
                Discover an uncertain source first with find_files or list_dir.""",
        whenNotToUse = "Do not use it to copy content or replace an existing destination.",
        resultContract =
                """
                Success returns JSON with `status`, the two requested paths as `source` and \
                `destination`, and `kind` (`file`, `directory`, or `symbolic_link`). In \
                detailed-result mode, failures use SOURCE_NOT_FOUND \
                (`Source path not found: <sourceAbsolutePath>`), ALREADY_EXISTS \
                (`Destination already exists: <destinationAbsolutePath>`), INVALID_DESTINATION \
                (`Invalid destination: a directory cannot be moved inside itself.` or \
                `Invalid destination: the destination parent is not an existing directory.`), \
                CROSS_FILESYSTEM_MOVE \
                (`Cross-filesystem move: source and destination are on different filesystems.`), \
                TREE_CHANGED (`Tree changed: ...`), UNSAFE_LINK \
                (`Unsafe link: symbolic links and reparse points cannot be followed.`), or \
                IO_ERROR (`I/O error: cannot move <sourceAbsolutePath> to its destination.`); \
                failure content is actionable plaintext in every mode.""",
        errorsAndEdgeCases =
                """
                The destination parent must already exist. A destination created concurrently is \
                not overwritten. Symbolic links are moved as links. Protected descendants reject a \
                directory move before mutation. If an entry changes after preflight, the move stops \
                with TREE_CHANGED before mutation.""",
        security =
                "An existing destination is never overwritten, and a cross-filesystem move fails rather than falling back to copy-and-delete, so the source is never lost mid-move.",
        examples = {
            "{\"sourceAbsolutePath\":\"/abs/project/old.txt\",\"destinationAbsolutePath\":\"/abs/project/new.txt\"}",
            "{\"sourceAbsolutePath\":\"/abs/project/downloads/report.pdf\",\"destinationAbsolutePath\":\"/abs/project/reports/report.pdf\"}",
            "{\"sourceAbsolutePath\":\"/abs/project/src/legacy\",\"destinationAbsolutePath\":\"/abs/project/archive/legacy\"}",
            "{\"sourceAbsolutePath\":\"/abs/project/notes.txt\",\"destinationAbsolutePath\":\"/abs/project/new.txt\"}"
        },
        returnExamples = {
            "{\"status\":\"moved\",\"source\":\"/abs/project/old.txt\",\"destination\":\"/abs/project/new.txt\",\"kind\":\"file\"}",
            "{\"status\":\"moved\",\"source\":\"/abs/project/downloads/report.pdf\",\"destination\":\"/abs/project/reports/report.pdf\",\"kind\":\"file\"}",
            "{\"status\":\"moved\",\"source\":\"/abs/project/src/legacy\",\"destination\":\"/abs/project/archive/legacy\",\"kind\":\"directory\"}",
            "Destination already exists: /abs/project/new.txt"
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
                    new Result(
                            "moved",
                            args.sourceAbsolutePath(),
                            args.destinationAbsolutePath(),
                            kind));
        } catch (NoSuchFileException e) {
            return ToolErrors.failure(
                    ToolErrorCode.WORKSPACE.SOURCE_NOT_FOUND,
                    "Source path not found: " + args.sourceAbsolutePath());
        } catch (FileAlreadyExistsException e) {
            return ToolErrors.failure(
                    ToolErrorCode.WORKSPACE.ALREADY_EXISTS,
                    "Destination already exists: " + args.destinationAbsolutePath());
        } catch (IOException e) {
            return ToolErrors.failure(
                    ToolErrorCode.WORKSPACE.IO_ERROR,
                    "I/O error: cannot move " + args.sourceAbsolutePath() + " to its destination.");
        }
    }

    public record Result(
            @NonNull String status,
            @NonNull String source,
            @NonNull String destination,
            @NonNull String kind) {}
}
