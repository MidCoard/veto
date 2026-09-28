/**
 * Typed startup registrations with host-attributed identity and deterministic ordering.
 *
 * <p>A contribution supplies an implementation for a contribution point. Registration is subject to
 * host validation and lifecycle admission and does not grant access to host resources.
 */
@DefaultQualifier(
        value = Nullable.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
package top.focess.veto.api.plugin.contribution;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
