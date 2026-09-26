package top.focess.veto.api.event;

/**
 * Handler execution order. A smaller {@link #weight()} runs earlier; within one priority, the host
 * preserves registration order.
 */
public enum EventPriority {
    /** Runs last, after every other priority. */
    LOWEST(6),
    /** Runs after {@link #LOW} through {@link #HIGHEST}. */
    LOWER(5),
    /** Runs before {@link #NORMAL}. */
    LOW(4),
    /** Default priority. */
    NORMAL(3),
    /** Runs before {@link #NORMAL}. */
    HIGH(2),
    /** Runs before {@link #HIGH}. */
    HIGHER(1),
    /** Runs first, before every other priority. */
    HIGHEST(0);

    private final int weight;

    EventPriority(int weight) {
        this.weight = weight;
    }

    /**
     * Returns the sort weight; a smaller value runs earlier.
     *
     * @return ordering weight
     */
    public int weight() {
        return weight;
    }
}
