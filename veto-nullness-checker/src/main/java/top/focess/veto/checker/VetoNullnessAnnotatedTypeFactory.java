package top.focess.veto.checker;

import com.sun.source.tree.MemberSelectTree;
import org.checkerframework.checker.nullness.NullnessAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.treeannotator.ListTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.TreeAnnotator;
import org.checkerframework.javacutil.TreeUtils;

/** Refines only class-literal expressions; all other nullness rules remain upstream defaults. */
public final class VetoNullnessAnnotatedTypeFactory extends NullnessAnnotatedTypeFactory {

    /** Creates the factory used by {@link VetoNullnessVisitor}. */
    public VetoNullnessAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker);
    }

    @Override
    protected TreeAnnotator createTreeAnnotator() {
        return new ListTreeAnnotator(
                super.createTreeAnnotator(),
                new TreeAnnotator(this) {
                    @Override
                    public Void visitMemberSelect(
                            MemberSelectTree tree, AnnotatedTypeMirror type) {
                        if (TreeUtils.isClassLiteral(tree)
                                && type instanceof AnnotatedDeclaredType declared) {
                            type.replaceAnnotation(NONNULL);
                            declared.getTypeArguments().getFirst().replaceAnnotation(NONNULL);
                        }
                        return null;
                    }
                });
    }
}
