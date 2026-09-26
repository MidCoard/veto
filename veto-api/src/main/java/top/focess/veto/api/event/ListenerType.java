package top.focess.veto.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a concrete {@link Listener} for automatic registration when its plugin is loaded. The host
 * instantiates an annotated listener through its public no-argument constructor.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ListenerType {}
