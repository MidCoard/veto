package top.focess.veto.api.resources;

import java.util.Optional;
import org.jspecify.annotations.NonNull;

/** Read-only catalogue grants. Shared roots are operator-configured, not plugin-chosen paths. */
public interface CatalogueAccess {
    /**
     * Finds an operator-configured shared read-only catalogue.
     *
     * @param name configured catalogue name
     * @return host-issued tree, or empty when no such grant exists
     */
    @NonNull Optional<CatalogueTree> shared(@NonNull String name);

    /**
     * Obtains this plugin's workspace catalogue for a live authorized invocation.
     *
     * @return bounded workspace tree for the selected session
     */
    @NonNull CatalogueTree workspace();
}
