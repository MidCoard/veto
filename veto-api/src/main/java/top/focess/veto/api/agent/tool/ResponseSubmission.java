package top.focess.veto.api.agent.tool;

import java.lang.annotation.*;

/** Declares an exclusive loop-control submission independently of the tool's name. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ResponseSubmission {
    Kind value();

    enum Kind {
        PLAN,
        ANSWER
    }
}
