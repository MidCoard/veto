package top.focess.veto.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.FrontendContribution;

/**
 * A builtin frontend whose browser module ships in this plugin's resources. Owns module loading;
 * subclasses own their scoped actions. Immutable and safe for concurrent module reads.
 */
public abstract class BundledFrontend implements FrontendContribution {
    private final @NonNull String resource;

    protected BundledFrontend(@NonNull String resource) {
        this.resource = resource;
    }

    @Override
    public final @NonNull String module() {
        try (var stream = BundledFrontend.class.getResourceAsStream(resource)) {
            if (stream == null)
                throw new IllegalStateException("Missing frontend module: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load frontend module: " + resource, failure);
        }
    }
}
