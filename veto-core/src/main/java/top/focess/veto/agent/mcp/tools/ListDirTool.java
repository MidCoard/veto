package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeMcpTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolSecurity;

/**
 * {@code list_dir} — list contents of a directory (files and child subdirectories). Transcribed
 * from.
 */
@Component
@ToolSecurity(risk = RiskCategory.READ_ONLY)
public final class ListDirTool implements NativeMcpTool<ListDirTool.Args> {

    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path to list contents of.")
                    String directoryPath) {}

    @Override
    public String getName() {
        return "list_dir";
    }

    @Override
    public String getDescription() {
        return "List contents of a directory (files and child subdirectories).";
    }

    @Override
    public Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public String execute(Args args) throws IOException {
        Path path = Path.of(args.directoryPath());
        if (!Files.isDirectory(path)) {
            return "{\"status\":\"error\",\"error\":\"Not a directory: "
                    + args.directoryPath()
                    + "\"}";
        }
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> stream = Files.list(path)) {
            stream.sorted()
                    .forEach(
                            p ->
                                    sb.append(p.getFileName())
                                            .append(Files.isDirectory(p) ? "/\n" : "\n"));
        }
        return sb.toString();
    }
}
