package top.focess.veto.builtin.process;

/** Result codes for stdin queue attempts. */
public enum InputStatus {
    QUEUED,
    TASK_NOT_FOUND,
    TASK_NOT_RUNNING,
    STDIN_CLOSED,
    INPUT_TOO_LARGE,
    INPUT_QUEUE_FULL
}
