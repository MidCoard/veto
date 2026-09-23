package top.focess.veto.api.agent.tool;

import java.lang.annotation.*;

/** Result carries the host-issued child execution identity. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ReaderExecutionResult {}
