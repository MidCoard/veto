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
 * {@code write_to_file} — create a new file or completely overwrite an existing file. Transcribed
 * from {@code mcp_tool_foundation.md} §10.3.
 */
@Component
@ToolSecurity(risk = RiskCategory.FILE_WRITE)
public final class WriteToFileTool implements NativeMcpTool<WriteToFileTool.Args> {

    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path of the file to write.")
                    String targetFile,
            @SecurityHint(ParamCategory.CODE_CONTENT) @Doc("The full content to write.")
                    String codeContent,
            @Doc("If false, refuse to overwrite an existing file.") boolean overwrite) {}

    @Override
    public String getName() {
        return "write_to_file";
    }

    @Override
    public String getDescription() {
        return "Create a new file or completely overwrite an existing file.";
    }

    @Override
    public Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public String execute(Args args) throws IOException {
        Path path = Path.of(args.targetFile());
        if (!args.overwrite() && Files.exists(path)) {
            return "{\"status\":\"error\",\"error\":\"File exists and overwrite=false: "
                    + args.targetFile()
                    + "\"}";
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, args.codeContent(), StandardCharsets.UTF_8);
        return "{\"status\":\"ok\",\"file\":\""
                + args.targetFile()
                + "\",\"bytes\":"
                + args.codeContent().getBytes(StandardCharsets.UTF_8).length
                + "}";
    }
}
