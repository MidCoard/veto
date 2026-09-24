package top.focess.veto.builtin.process;

import java.time.*;
import java.util.*;
import top.focess.veto.api.agent.tool.*;

public enum InputStatus {
    QUEUED,
    TASK_NOT_FOUND,
    TASK_NOT_RUNNING,
    STDIN_CLOSED,
    INPUT_TOO_LARGE,
    INPUT_QUEUE_FULL
}
