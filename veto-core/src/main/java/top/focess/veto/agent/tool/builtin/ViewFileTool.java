package top.focess.veto.agent.tool.builtin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.ProtectedWorkspaceReadCapabilityImpl;
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
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;
import top.focess.veto.agent.tool.WorkspaceReadTool;
import top.focess.veto.vault.SecretCandidateStore;

/** {@code view_file} — read lines of a text file from the local filesystem. */
@Component
@ToolSecurity(capability = ToolCapability.WORKSPACE_READ, defaultDanger = Danger.SAFE)
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description = "Read lines of a text file from the local filesystem.",
        behavior =
                "Read UTF-8, replacing detected secrets with session references before selecting lines. "
                        + "startLine/endLine are inclusive, 1-indexed; omitted bounds mean first/last line. "
                        + "Bounds clamp to the file; reversed or out-of-file ranges are empty. "
                        + "The whole input must fit 16 MiB (16,777,216 bytes), even for a line range. "
                        + "Output stops at 5000 lines or 1000000 characters with "
                        + "`[truncated; request a narrower line range]`.",
        whenToUse =
                "Inspect text or read current source before editing; numbered lines support replace_file_content.",
        whenNotToUse =
                "Use grep_search for cross-file patterns and list_dir for discovery. UTF-8 text only; read-only.",
        resultContract =
                """
                    - Success: one output line per source line as \
                    `<lineNumber>: <line text>` (1-indexed). An empty range yields no lines.
                    - Supplied `absolutePath` does not exist or is not a regular file (failure): \
                    `Not a regular file: <absolutePath>`.
                    - Oversized file (failure): \
                    `File exceeds 16 MiB (16,777,216 bytes); request a smaller artifact`.
                    - Invalid `absolutePath` syntax (failure): `Invalid path: <absolutePath>`.
                    - Invalid UTF-8 (failure): `File is not valid UTF-8: <absolutePath>`.
                    - Read failure (failure): `Cannot read file: <absolutePath>`.
                    """,
        errorsAndEdgeCases =
                """
                    - After a path rejection, do not retry a similar guess. Return to the last successful \
                    parent listing and reconstruct the absolute path from observed file names.
                    - `startLine` greater than the file length -> no output (range clamped to empty).
                    - `endLine` less than `startLine` -> no output.
                    - Directories, device files, and sockets are rejected as "not a regular file".
                    """,
        security =
                "Read-only. Follow the current Boundaries rules. If access is refused, change scope instead of retrying the same path.",
        examples = {
            "{\"absolutePath\": \"/abs/src/Main.java\"}",
            "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 10, \"endLine\": 20}",
            "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 100}",
            "{\"absolutePath\": \"/abs/config/app.yml\", \"endLine\": 30}"
        },
        returnExamples = {"1: package com.example;\n2: \n3: public class Main {"})
public final class ViewFileTool implements WorkspaceReadTool<ViewFileTool.Args> {
    private final @NonNull WorkspaceReadCapability protectedFiles;

    @Autowired
    public ViewFileTool(@NonNull WorkspaceReadCapability protectedFiles) {
        this.protectedFiles = protectedFiles;
    }

    public ViewFileTool() {
        this(new ProtectedWorkspaceReadCapabilityImpl(new SecretCandidateStore()));
    }

    /** Parameter container for {@code view_file}. */
    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH)
                    @Doc("The absolute path of the file to view.")
                    @NonNull String absolutePath,
            @Doc("1-indexed starting line (inclusive).") Integer startLine,
            @Doc("1-indexed ending line (inclusive).") Integer endLine) {}

    @Override
    public @NonNull String getName() {
        return "view_file";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull WorkspaceReadCapability workspace) {
        try {
            WorkspaceFile file = workspace.file(args.absolutePath());
            if (!file.kind().equals("file")) {
                return ToolErrors.failure(
                        "NOT_A_FILE", "Not a regular file: " + args.absolutePath());
            }
            if (file.size() > 16L * 1024 * 1024) {
                return ToolErrors.failure(
                        "FILE_TOO_LARGE",
                        "File exceeds 16 MiB (16,777,216 bytes); request a smaller artifact");
            }
            Integer start = args.startLine();
            Integer end = args.endLine();
            int from = start == null ? 1 : Math.max(1, start);
            int until = end == null ? Integer.MAX_VALUE : end;
            if (until < from) return "";
            StringBuilder output = new StringBuilder();
            int emitted = 0;
            byte[] bytes;
            try (var input = file.openRead()) {
                bytes = input.readNBytes(16 * 1024 * 1024 + 1);
            }
            if (bytes.length > 16 * 1024 * 1024) {
                return ToolErrors.failure(
                        "FILE_TOO_LARGE",
                        "File exceeds 16 MiB (16,777,216 bytes); request a smaller artifact");
            }
            String original =
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            String protectedText;
            try {
                protectedText = protectedFiles.captureFileText(original);
            } catch (RuntimeException failure) {
                return ToolErrors.failure(
                        "PROTECTED_INPUT_UNAVAILABLE",
                        "Protected file content could not be processed; retry or use credential settings");
            }
            try (var reader = new BufferedReader(new StringReader(protectedText))) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    if (number < from) continue;
                    if (number > until) break;
                    String rendered = number + ": " + line + "\n";
                    if (emitted >= 5000 || output.length() + rendered.length() > 1_000_000) {
                        output.append("[truncated; request a narrower line range]\n");
                        break;
                    }
                    output.append(rendered);
                    emitted++;
                }
            }
            return output.toString();
        } catch (MalformedInputException e) {
            return ToolErrors.failure(
                    "INVALID_UTF8", "File is not valid UTF-8: " + args.absolutePath());
        } catch (NoSuchFileException e) {
            return ToolErrors.failure("NOT_A_FILE", "Not a regular file: " + args.absolutePath());
        } catch (IOException e) {
            return ToolErrors.failure("IO_ERROR", "Cannot read file: " + args.absolutePath());
        }
    }
}
