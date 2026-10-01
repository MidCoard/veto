/**
 * Named, major-versioned JSON protocols for plugin-to-plugin calls.
 *
 * <p>Providers can register during construction. Consumers discover providers at start or later,
 * after the host binds the directory. Handles recheck caller and provider admission on every call.
 */
@DefaultQualifier(
        value = Nullable.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
package top.focess.veto.api.plugin.service;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
