package top.focess.veto.api.agent.capability;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.web.FinishReadArgs;

/** The reader's single-document operations; no URL, filesystem, or delegation API. */
public interface WebDocumentCapability extends Capability {
    @NonNull String fetchPage();

    @NonNull String findSections(@NonNull String query);

    @NonNull String readSections(@NonNull List<@NonNull String> ids);

    @NonNull String finish(@NonNull FinishReadArgs result);
}
