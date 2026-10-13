/**
 * ZeroMQ channels are not thread-safe. Each instance requires one exclusive owner for send, recv
 * and close. A thread start/join handoff may transfer ownership without overlapping access; context
 * lifecycle ownership does not change this rule. Use ProtocolClient for concurrent callers.
 */
@DefaultQualifier(
        value = Nullable.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
package top.focess.veto.transport.zmq;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
