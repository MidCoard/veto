package top.focess.veto.builtin.monitor;

import top.focess.veto.api.agent.tool.ToolErrorCode;

/** Stable feature error names; the host need not recognize monitor operations. */
public enum MonitorError implements ToolErrorCode {
    GROUP_MANAGED,
    LIMIT_EXCEEDED,
    UNKNOWN
}
