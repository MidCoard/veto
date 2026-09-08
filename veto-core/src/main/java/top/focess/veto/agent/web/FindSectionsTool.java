package top.focess.veto.agent.web;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.WebDocumentCapability;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolSecurity;
import top.focess.veto.agent.tool.WebDocumentTool;

/** Invocation-local tool; deliberately not registered as a Spring component. */
@ToolSecurity(capability = ToolCapability.NETWORK_EGRESS, defaultDanger = Danger.SAFE)
final class FindSectionsTool implements WebDocumentTool<WebReader.Find> {
    private final @NonNull WebDocumentCapability document;

    FindSectionsTool(@NonNull WebDocumentCapability document) {
        this.document = document;
    }

    @Override
    public @NonNull String getName() {
        return "find_sections";
    }

    // Class literals are non-null despite the checker's package-default interpretation.
    @SuppressWarnings("nullness:return")
    @Override
    public @NonNull Class<WebReader.Find> getArgsClass() {
        return WebReader.Find.class;
    }

    @Override
    public @NonNull WebDocumentCapability documentCapability() {
        return document;
    }

    @Override
    public @NonNull String execute(
            WebReader.@NonNull Find args, @NonNull WebDocumentCapability capability) {
        return capability.findSections(args.query());
    }
}
