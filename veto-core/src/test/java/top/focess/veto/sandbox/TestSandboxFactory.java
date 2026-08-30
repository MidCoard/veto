package top.focess.veto.sandbox;

import org.jspecify.annotations.NonNull;

/** Test-only construction helpers; never available in a production artifact. */
public final class TestSandboxFactory {

    private TestSandboxFactory() {}

    /**
     * Returns the no-kernel-wall substrate used only by narrow unit tests. Integration tests must
     * use {@link #platformSandbox()}.
     */
    public static @NonNull ConstrainedSubprocessSubstrate uncontainedSubprocesses() {
        return new ConstrainedSubprocessSubstrate();
    }

    /** Returns the production platform wall, including the Windows AppContainer launch chain. */
    public static @NonNull ConstrainedSubprocessSubstrate platformSandbox() {
        return new ConstrainedSubprocessSubstrate(new KernelSandboxSubstrate());
    }
}
