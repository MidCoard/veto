package top.focess.veto.agent.mcp.tools;

import jakarta.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeMcpTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolSecurity;

/**
 * {@code view_file} — read lines of a text file from the local filesystem. {@code
 * mcp_tool_foundation.md}.
 */
@Component
@ToolSecurity(risk = RiskCategory.READ_ONLY)
public final class ViewFileTool implements NativeMcpTool<ViewFileTool.Args> {

    /** Parameter container for {@code view_file}. */
    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH)
                    @Doc("The absolute path of the file to view.")
                    String absolutePath,
            @Nullable @Doc("1-indexed starting line (inclusive).") Integer startLine,
            @Nullable @Doc("1-indexed ending line (inclusive).") Integer endLine) {}

    @Override
    public String getName() {
        return "view_file";
    }

    @Override
    public String getDescription() {
        return "Read lines of a text file from the local filesystem.";
    }

    @Override
    public Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public String execute(Args args) throws IOException {
        Path path = Path.of(args.absolutePath());
        if (!Files.isRegularFile(path)) {
            return "{\"status\":\"error\",\"error\":\"Not a regular file: "
                    + args.absolutePath()
                    + "\"}";
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int from = args.startLine() == null ? 1 : Math.max(1, args.startLine());
        int to = args.endLine() == null ? lines.size() : Math.min(lines.size(), args.endLine());
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(i).append(": ").append(lines.get(i - 1)).append('\n');
        }
        return sb.toString();
    }
}
