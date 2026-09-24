package top.focess.veto.api.agent.tool;

import java.lang.annotation.*;

/** A generic exclusive control transfer, independent of feature or resolved tool name. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ControlSubmission {
    Kind value();

    enum Kind {
        EXECUTE,
        FINISH
    }
}
