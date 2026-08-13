package top.focess.veto.command.data;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.command.data.DataBuffer;

/**
 * A generic {@link DataBuffer} backed by an object array, for argument types that have no dedicated
 * buffer in the focess-command framework (e.g. enums parsed via {@link
 * top.focess.command.DataConverter#ofEnum}).
 *
 * <p>The framework pre-registers buffers only for its built-in converters (String, Integer, Long,
 * Double, Boolean). A custom converter's target class still needs a buffer so {@link
 * top.focess.command.DataCollection} can allocate storage for it during parsing; this buffer fills
 * that role for any reference type and is paired with the converter via {@link
 * top.focess.command.DataCollection#register}.
 *
 * @param <T> the element type
 */
public final class ObjectBuffer<T> extends DataBuffer<T> {

    private final @NonNull Object[] values;
    private int pos;

    private ObjectBuffer(final int size) {
        this.values = new Object[size];
    }

    /**
     * Allocate an {@code ObjectBuffer} with the given fixed capacity.
     *
     * @param size the number of values the buffer must hold
     * @param <T> the element type
     * @return a new {@code ObjectBuffer}
     */
    public static <T> @NonNull ObjectBuffer<T> allocate(final int size) {
        return new ObjectBuffer<>(size);
    }

    @Override
    public void flip() {
        this.pos = 0;
    }

    @Override
    public void put(@Nullable final T t) {
        if (this.pos < this.values.length) {
            this.values[this.pos] = t;
            this.pos++;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable T get() {
        if (this.pos < this.values.length) {
            return (T) this.values[this.pos++];
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable T get(final int index) {
        if (index >= 0 && index < this.values.length) {
            return (T) this.values[index];
        }
        return null;
    }
}
