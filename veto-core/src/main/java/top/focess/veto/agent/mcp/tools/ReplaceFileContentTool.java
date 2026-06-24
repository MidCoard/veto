package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeMcpTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolSecurity;

/**
 * {@code replace_file_content} — replace a single contiguous block of text in an existing file.
 * Transcribed from {@code mcp_tool_foundation.md} §10.4.
 */
@Component
@ToolSecurity(risk = RiskCategory.FILE_WRITE)
public final class ReplaceFileContentTool implements NativeMcpTool<ReplaceFileContentTool.Args> {

    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path of the file to patch.")
                    String targetFile,
            @Doc("1-indexed starting line (inclusive).") int startLine,
            @Doc("1-indexed ending line (inclusive).") int endLine,
            @SecurityHint(ParamCategory.CODE_CONTENT) @Doc("Exact text range to replace.")
                    String targetContent,
            @SecurityHint(ParamCategory.CODE_CONTENT) @Doc("The replacement text.")
                    String replacementContent) {}

    @Override
    public String getName() {
        return "replace_file_content";
    }

    @Override
    public String getDescription() {
        return "Replace a single contiguous block of code in an existing file.";
    }

    @Override
    public Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public String execute(Args args) throws IOException {
        Path path = Path.of(args.targetFile());
        if (!Files.isRegularFile(path)) {
            return "{\"status\":\"error\",\"error\":\"Not a regular file: "
                    + args.targetFile()
                    + "\"}";
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        int idx = content.indexOf(args.targetContent());
        if (idx < 0) {
            return "{\"status\":\"error\",\"error\":\"targetContent not found in file.\"}";
        }
        String updated =
                content.substring(0, idx)
                        + args.replacementContent()
                        + content.substring(idx + args.targetContent().length());
        Files.writeString(path, updated, StandardCharsets.UTF_8);
        return "{\"status\":\"ok\",\"file\":\"" + args.targetFile() + "\"}";
    }
}
