package top.focess.veto.checker;

import javax.annotation.processing.SupportedOptions;
import org.checkerframework.checker.nullness.NullnessChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;

/** Applies the standard nullness checker with a narrow rule for Java class literals. */
@SupportedOptions("invocationPreservesArgumentNullness")
public final class VetoNullnessChecker extends NullnessChecker {

    @Override
    protected BaseTypeVisitor<?> createSourceVisitor() {
        return new VetoNullnessVisitor(this);
    }
}
