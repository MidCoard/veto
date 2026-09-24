package top.focess.veto.builtin.web;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.builtin.web.model.FinishReadArgs;

/** The reader's single-document operations; no URL, filesystem, or delegation API. */
public interface WebDocumentCapability {
    @NonNull String fetchPage();

    @NonNull String findSections(@NonNull String query);

    @NonNull String readSections(@NonNull List<@NonNull String> ids);

    @NonNull String finish(@NonNull FinishReadArgs result);
}
