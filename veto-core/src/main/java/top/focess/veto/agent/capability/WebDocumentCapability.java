package top.focess.veto.agent.capability;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.web.WebReader;

/** The reader's single-document operations; no URL, filesystem, or delegation API. */
public non-sealed interface WebDocumentCapability extends Capability {
    @NonNull String fetchPage();

    @NonNull String findSections(@NonNull String query);

    @NonNull String readSections(@NonNull List<@NonNull String> ids);

    @NonNull String finish(WebReader.@NonNull Finish result);
}
