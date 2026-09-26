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
        /** Push a plugin-authored model flow after the current host call. */
        PUSH,
        /** Finish the current call with a final response. */
        FINISH
    }
}
