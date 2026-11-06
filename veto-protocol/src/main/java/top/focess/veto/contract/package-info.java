/**
 * Shared Java JSON protocol contracts. Frame defines messages and FrameCodec handles serialization.
 * Backend WebSocket admission and dispatch live in core; terminal connection management and
 * rendering live in the TypeScript frontend repository.
 *
 * <p>FrameCodec supports concurrent calls with stable inputs; Version is immutable. Frame and Delta
 * document their mutable payload exceptions. Passing a record across a thread boundary does not
 * freeze its maps or JSON trees.
 *
 * <p>Request seq correlates an exchange; Delta.sequence orders output. Neither is a transport
 * packet number. The JSON byte limit applies to a complete frame. Completing a transport write does
 * not promise server acceptance of a command.
 */
@DefaultQualifier(
        value = Nullable.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
package top.focess.veto.contract;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
