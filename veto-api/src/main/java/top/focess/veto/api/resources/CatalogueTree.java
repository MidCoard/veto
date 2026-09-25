package top.focess.veto.api.resources;

import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** A host-issued, bounded read-only tree. Identifiers are opaque and grant no OS path access. */
public interface CatalogueTree {
    /**
     * Returns the opaque identity of this granted tree.
     *
     * @return host-issued tree identity
     */
    @NonNull String identity();

    /**
     * Lists bounded files matching a directory and file name within this tree.
     *
     * @param directory relative directory within the grant
     * @param fileName requested file name
     * @return matching file handles
     * @throws IOException if the host cannot enumerate the granted tree
     */
    @NonNull List<@NonNull File> files(@NonNull String directory, @NonNull String fileName)
            throws IOException;

    /** Opaque handle to one file within the granted tree. */
    interface File {
        /**
         * Returns the host-issued file identity.
         *
         * @return opaque file identity
         */
        @NonNull String identity();

        /**
         * Returns the file path relative to the granted tree.
         *
         * @return relative display path
         */
        @NonNull String relativePath();

        /**
         * Reads text after revalidating root containment and file identity.
         *
         * @return bounded file content
         * @throws IOException if the file changed or cannot be read
         */
        @NonNull String read() throws IOException;
    }
}
