/**
 * Shared frames, codecs and connection contracts.
 *
 * <p>ProtocolClient supports concurrent public calls; FrameCodec supports concurrent calls with
 * stable inputs; Version is immutable. Frame and Delta document their mutable payload exceptions.
 * FrameTransport permits one reader, one writer and concurrent close on each connection. Passing a
 * record across a thread boundary does not freeze its maps or JSON trees.
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
