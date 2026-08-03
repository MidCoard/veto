package top.focess.veto.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves VETO.md ("The Law"): per-root merge of {@code <root>/VETO.md} (primary) + {@code
 * <root>/.veto/VETO.md} (override — appended last, so its rules win), then cross-root concatenation
 * in root order. Read at compile time by the PromptCompiler (config, bypasses tool-call screening).
 * A root with neither contributes nothing.
 */
public record VetoMdResolver(@NonNull List<WorkspaceRoot> roots) {

    private static final Logger log = LoggerFactory.getLogger(VetoMdResolver.class);

    /** Resolves the concatenated Law across all roots (empty string if none). */
    public @NonNull String resolve() {
        StringBuilder sb = new StringBuilder();
        for (WorkspaceRoot root : roots) {
            String perRoot = resolveRoot(root.hostPath());
            if (!perRoot.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(perRoot);
            }
        }
        return sb.toString();
    }

    private @NonNull String resolveRoot(@NonNull Path rootHost) {
        StringBuilder sb = new StringBuilder();
        appendIfReadable(sb, rootHost.resolve("VETO.md"));
        appendIfReadable(sb, rootHost.resolve(".veto/VETO.md"));
        return sb.toString().strip();
    }

    private void appendIfReadable(@NonNull StringBuilder sb, @NonNull Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return;
        }
        try {
            String content = Files.readString(file).strip();
            if (!content.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(content);
            }
        } catch (IOException e) {
            log.warn("Could not read {} — skipping", file, e);
        }
    }
}
