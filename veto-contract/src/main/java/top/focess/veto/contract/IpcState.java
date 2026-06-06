package top.focess.veto.contract;

/**
 * State byte for a unidirectional IPC file. Each file flows in only one direction, so only two
 * states are needed.
 */
public enum IpcState {

    /** No data pending — the writer may write. */
    IDLE((byte) 0),

    /** Data is ready — the reader should read. */
    READY((byte) 1);

    private final byte code;

    IpcState(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static IpcState from(byte code) {
        return switch (code) {
            case 0 -> IDLE;
            case 1 -> READY;
            default -> throw new IllegalArgumentException("Unknown IpcState code: " + code);
        };
    }
}
