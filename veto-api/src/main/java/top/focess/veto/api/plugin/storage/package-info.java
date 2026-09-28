/**
 * Host-persisted, plugin-namespaced application, user, and session data.
 *
 * <p>Scope values are host-issued grants rather than caller-chosen identifiers. Stores use revision
 * checks and revalidate scope and plugin admission; persistence does not imply that an old handle
 * remains authorized.
 */
@DefaultQualifier(
        value = Nullable.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
package top.focess.veto.api.plugin.storage;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
