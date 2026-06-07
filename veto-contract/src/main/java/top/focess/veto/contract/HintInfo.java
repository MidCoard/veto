package top.focess.veto.contract;

/**
 * Result of a hint request.
 *
 * @param placeholder the expected argument placeholder (e.g. {@code <user>} or {@code [pass]})
 * @param description optional human-readable description of the argument
 */
public record HintInfo(String placeholder, String description) {

    public static final HintInfo EMPTY = new HintInfo(null, null);

    public boolean isEmpty() {
        return placeholder == null || placeholder.isBlank();
    }

    public String displayText() {
        if (isEmpty()) return null;
        if (description != null && !description.isBlank()) {
            return placeholder + " — " + description;
        }
        return placeholder;
    }
}
