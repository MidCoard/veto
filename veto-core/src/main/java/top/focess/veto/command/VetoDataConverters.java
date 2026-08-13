package top.focess.veto.command;

import org.jspecify.annotations.NonNull;
import top.focess.command.DataCollection;
import top.focess.command.DataConverter;
import top.focess.veto.command.data.ObjectBuffer;
import top.focess.veto.model.tier.ModelTier;

/**
 * Veto-specific {@link DataConverter} instances and their buffer registrations.
 *
 * <p>The focess-command framework only pre-registers {@link top.focess.command.data.DataBuffer
 * DataBuffers} for its built-in converters (String, Integer, Long, Double, Boolean). Any custom
 * converter, such as {@link DataConverter#ofEnum} for {@link ModelTier}, must be paired with a
 * {@link top.focess.command.DataCollection.BufferGetter BufferGetter} so the framework can allocate
 * storage for its target class when it builds the {@link DataCollection}. This holder performs that
 * registration once at class-load time and exposes the converter instances for use in {@link
 * top.focess.command.CommandArgument CommandArguments}.
 *
 * <p>Each converter here is a single shared instance: registration binds the buffer to that exact
 * instance, so callers must reference these constants rather than calling {@code ofEnum} again.
 */
public final class VetoDataConverters {

    /**
     * Converts a {@link ModelTier} name case-insensitively and tab-completes with the enum
     * constants. Used by {@code /pattern create <name> <tier>}.
     */
    public static final @NonNull DataConverter<ModelTier> MODEL_TIER =
            DataConverter.ofEnum(ModelTier.class);

    static {
        DataCollection.register(MODEL_TIER, ObjectBuffer::allocate);
    }

    private VetoDataConverters() {}
}
