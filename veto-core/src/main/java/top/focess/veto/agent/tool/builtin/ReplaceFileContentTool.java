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

/** {@code replace_file_content} — replace a single contiguous block of text in an existing file. */
@Component
@ToolSecurity(capability = ToolCapability.WORKSPACE_WRITE, defaultDanger = Danger.ELEVATED)
public final class ReplaceFileContentTool
        implements WorkspaceWriteTool<ReplaceFileContentTool.Args> {

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Replace a single contiguous block of code in an existing file.",
            behavior =
                    """
                    Reads `absolutePath` as UTF-8 and searches only the inclusive `startLine`/`endLine` range. \
                    Exactly one occurrence of `targetContent` must exist inside that range. The updated content \
                    is written through a same-directory temporary file and replacement move. An empty \
                    `replacementContent` deletes the matched block. Both the original file and the resulting \
                    UTF-8 content are limited to 16 MiB (16,777,216 bytes). The limit bounds the complete \
                    in-memory edit and the temporary-file write performed by one call.
                    """,
            whenToUse =
                    """
                    Use `replace_file_content` to make a localized, surgical edit to an existing file - renaming \
                    a symbol in one spot, fixing a few lines, or swapping a block for new text. It targets a \
                    contiguous range and replaces the unique exact occurrence of `targetContent` within that \
                    selected range. Prefer it over `write_to_file` when most of the file is unchanged.

                    Always `view_file` the target range first so your `targetContent` matches the file exactly \
                    (whitespace included).
                    """,
            whenNotToUse =
                    """
                    - Do not use `replace_file_content` to create a file or rewrite most of it - use \
                    `write_to_file`.
                    - Do not use it blind - if `targetContent` does not match the file byte-for-byte, the call \
                    fails. Read first.
                    - Do not use it to replace ALL occurrences. For multiple \
                    sites, call it repeatedly or use `write_to_file`.
                    - Do not pass a `targetContent` so short it could match unintended locations (e.g. a bare \
                    `}`); include enough surrounding context to be unique.
                    """,
            resultContract =
                    """
                    - Success: `{"status":"ok","file":"<absolutePath>"}`.
                    - Invalid `absolutePath`, range, or replacement (failure): one of \
                    `Not a regular file: <absolutePath>`, `File exceeds 16 MiB (16,777,216 bytes)`, \
                    `Invalid line range`, `Line range outside file`, or \
                    `targetContent must not be empty` or \
                    `Replacement exceeds 16 MiB (16,777,216 bytes)`.
                    - Match failure (failure): \
                    `targetContent not found in selected range.` or \
                    `targetContent is not unique in selected range.` The file remains unchanged.
                    """,
            errorsAndEdgeCases =
                    """
                    - After a match failure, reread the selected range and quote enough surrounding context \
                    to make the target unique before retrying.
                    - Only regular files are editable; discover the target with `list_dir` and inspect it \
                    with `view_file` first.
                    - `targetContent` and `replacementContent` are exact (whitespace, indentation, newlines all \
                    matter). A mismatched indent means "not found".
                    - `startLine`/`endLine` must form a valid inclusive range and restrict the search.
                    - Replacing the directory entry can replace filesystem metadata. Symbolic-link and \
                    Windows reparse-point targets are rejected rather than followed or replaced.
                    """,
            security =
                    """
                    `absolutePath` is a FILESYSTEM_PATH; `targetContent` and `replacementContent` are CODE_CONTENT. \
                    The Gateway canonicalizes the path and applies deployer-policy and semantic screening before \
                    the write. Under FULL_ACCESS, workspace roots are working context rather than a path boundary, \
                    so any absolute host path may be targeted; restrictive policies may fence paths. The operation \
                    is exposed through a call-scoped `WORKSPACE_WRITE` capability and has default danger \
                    `ELEVATED`; it is audited and may require approval. If the Gateway \
                    actually refuses a path, change approach instead; never smuggle disallowed content.
                    """,
            examples = {
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 5, \"endLine\": 8, \"targetContent\": \"old\", \"replacementContent\": \"new\"}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 12, \"endLine\": 12, \"targetContent\": \"int x = 1;\", \"replacementContent\": \"int x = 2;\"}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 1, \"endLine\": 1, \"targetContent\": \"package old;\", \"replacementContent\": \"package new;\"}",
                "{\"absolutePath\": \"/abs/README.md\", \"startLine\": 3, \"endLine\": 3, \"targetContent\": \"# Old Title\", \"replacementContent\": \"# New Title\"}",
                "{\"absolutePath\": \"/abs/config/app.yml\", \"startLine\": 10, \"endLine\": 10, \"targetContent\": \"port: 8080\", \"replacementContent\": \"port: 8443\"}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 20, \"endLine\": 24, \"targetContent\": \"// TODO\\n\", \"replacementContent\": \"// done\\n\"}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 8, \"endLine\": 8, \"targetContent\": \"    return null;\", \"replacementContent\": \"    return value;\"}"
            },
            returnExamples = {"{\"status\":\"ok\",\"file\":\"/abs/src/Main.java\"}"})
    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path of the file to patch.")
                    @NonNull String absolutePath,
            @Required @Doc("1-indexed starting line (inclusive).") int startLine,
            @Required @Doc("1-indexed ending line (inclusive).") int endLine,
            @SecurityHint(ParamCategory.CODE_CONTENT) @Doc("Exact text range to replace.")
                    @NonNull String targetContent,
            @SecurityHint(ParamCategory.CODE_CONTENT) @Doc("The replacement text.")
                    @NonNull String replacementContent) {}

    @Override
    public @NonNull String getName() {
        return "replace_file_content";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull WorkspaceWriteCapability capability) {
        return capability.replaceText(
                "absolutePath",
                args.startLine(),
                args.endLine(),
                args.targetContent(),
                args.replacementContent());
    }
}
