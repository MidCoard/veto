package top.focess.veto.tui;

import org.jetbrains.annotations.NotNull;
import org.jline.terminal.Size;
import top.focess.veto.contract.IpcFrame;

/** Sealed interface representing all events processed by the single-threaded event loop. */
public sealed interface TuiEvent {
    record KeyInput(int keyChar, @NotNull String keyName) implements TuiEvent {}

    record Resize(@NotNull Size size) implements TuiEvent {}

    record ZmqMessage(@NotNull IpcFrame.ServerFrame frame) implements TuiEvent {}

    record HeartbeatTick() implements TuiEvent {}

    record Shutdown() implements TuiEvent {}
}
