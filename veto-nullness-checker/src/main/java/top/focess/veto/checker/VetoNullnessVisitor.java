package top.focess.veto.checker;

import org.checkerframework.checker.nullness.NullnessVisitor;
import org.checkerframework.common.basetype.BaseTypeChecker;

/** Supplies Veto's annotated type factory to the ordinary nullness visitor. */
public final class VetoNullnessVisitor extends NullnessVisitor {

    /** Creates a visitor for the given checker. */
    public VetoNullnessVisitor(BaseTypeChecker checker) {
        super(checker);
    }

    @Override
    public VetoNullnessAnnotatedTypeFactory createTypeFactory() {
        return new VetoNullnessAnnotatedTypeFactory(checker);
    }
}
