package top.focess.veto.agent.capability;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.web.FinishReadTool;
import top.focess.veto.api.agent.capability.Capability;

/** The reader's single-document operations; no URL, filesystem, or delegation API. */
public interface WebDocumentCapability extends Capability {
    @NonNull String fetchPage();

    @NonNull String findSections(@NonNull String query);

    @NonNull String readSections(@NonNull List<@NonNull String> ids);

    @NonNull String finish(FinishReadTool.@NonNull Args result);
}
