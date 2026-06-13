package top.focess.veto.tui;

import org.jline.terminal.Size;
import top.focess.veto.contract.IpcFrame;

/** Sealed interface representing all events processed by the single-threaded event loop. */
public sealed interface TuiEvent {
    record KeyInput(int keyChar, String keyName) implements TuiEvent {}

    record Resize(Size size) implements TuiEvent {}

    record ZmqMessage(IpcFrame.ServerFrame frame) implements TuiEvent {}

    record HeartbeatTick() implements TuiEvent {}

    record Shutdown() implements TuiEvent {}
}
