/**
 * Trusted in-process plugin lifecycle and host binding contracts.
 *
 * <p>Contributions publish implementations but grant no authority. {@link
 * PluginContext#service(Class)} exposes optional host-granted Java capabilities, while {@link
 * PluginContext#services()} exposes named JSON protocols implemented by plugins. Neither boundary
 * sandboxes arbitrary Java code.
 */
@DefaultQualifier(
        value = Nullable.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
package top.focess.veto.api.plugin;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
