/**
 * Host-mediated, session-bound agent execution for admitted plugins.
 *
 * <p>Profiles express intent; the host retains authority over identity, available tools, model
 * selection, budgets, cancellation, recovery, and termination.
 */
@DefaultQualifier(
        value = Nullable.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
package top.focess.veto.api.plugin.agent;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
