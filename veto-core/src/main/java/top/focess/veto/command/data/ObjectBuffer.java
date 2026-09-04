package top.focess.veto.command.data;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.jspecify.annotations.NonNull;
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

    private final @NonNull List<T> values;
    private final int capacity;
    private int pos;

    private ObjectBuffer(final int size) {
        this.values = new ArrayList<>(size);
        this.capacity = size;
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
    public void put(final T t) {
        if (this.pos < this.capacity) {
            if (this.pos < this.values.size()) {
                this.values.set(this.pos, t);
            } else {
                this.values.add(t);
            }
            this.pos++;
        }
    }

    @Override
    public T get() {
        if (this.pos >= this.values.size()) {
            throw new NoSuchElementException("ObjectBuffer has no remaining value");
        }
        return this.values.get(this.pos++);
    }

    @Override
    public T get(final int index) {
        return this.values.get(index);
    }
}
