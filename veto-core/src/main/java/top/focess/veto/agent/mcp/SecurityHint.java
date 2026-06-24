package top.focess.veto.agent.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares what category of security check a native tool parameter requires. Read by the Gateway to
 * decide how to screen an individual argument.
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SecurityHint {

    /** What category of security check this parameter requires. */
    ParamCategory value();
}
