package top.focess.veto.api.resources;

import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** A host-issued, bounded read-only tree. Identifiers are opaque and grant no OS path access. */
public interface CatalogueTree {
    @NonNull String identity();

    @NonNull List<@NonNull File> files(@NonNull String directory, @NonNull String fileName)
            throws IOException;

    interface File {
        @NonNull String identity();

        @NonNull String relativePath();

        /** Revalidates root containment and the selected file identity on every read. */
        @NonNull String read() throws IOException;
    }
}
