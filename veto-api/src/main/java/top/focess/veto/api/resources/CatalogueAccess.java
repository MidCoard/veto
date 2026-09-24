package top.focess.veto.api.resources;

import java.util.Optional;
import org.jspecify.annotations.NonNull;

/** Read-only catalogue grants. Shared roots are operator-configured, not plugin-chosen paths. */
public interface CatalogueAccess {
    @NonNull Optional<CatalogueTree> shared(@NonNull String name);

    /** Requires this plugin's live authorized invocation in its selected session. */
    @NonNull CatalogueTree workspace();
}
