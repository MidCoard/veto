package top.focess.veto.api.agent.tool;

import java.lang.annotation.*;

/** A generic exclusive control transfer, independent of feature or resolved tool name. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ControlSubmission {
    /**
     * Declares how the annotated tool transfers control.
     *
     * @return the exclusive control action performed by the annotated tool
     */
    Kind value();

    /** Exclusive ways in which a tool can transfer control of the current call. */
    enum Kind {
        /** Execute a plugin-authored workflow under the current host call. */
        EXECUTE,
        /** Finish the current call with a final response. */
        FINISH
    }
}
