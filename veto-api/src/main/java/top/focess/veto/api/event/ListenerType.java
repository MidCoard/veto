package top.focess.veto.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional marker on a concrete {@link Listener}. The current host does not scan this annotation or
 * instantiate marked classes; a plugin must explicitly register its listener instance at the
 * listeners contribution point.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ListenerType {}
