package top.focess.veto.contract;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;

final class ContractTestSupport {

    private ContractTestSupport() {}

    @SuppressWarnings("nullness")
    static <T extends @NonNull Object> @NonNull T assertInstanceOf(
            Class<T> expectedType, Object actual) {
        if (expectedType == null) {
            throw new AssertionError("Class token should not be null");
        }
        return Assertions.assertInstanceOf(expectedType, actual);
    }

    static <T extends @NonNull Throwable> @NonNull T assertThrows(
            Class<T> expectedType, @NonNull Executable executable) {
        if (expectedType == null) {
            throw new AssertionError("Class token should not be null");
        }
        try {
            executable.execute();
        } catch (Throwable thrown) {
            return assertInstanceOf(expectedType, thrown);
        }
        throw new AssertionError("Expected " + expectedType.getName() + " to be thrown");
    }
}
