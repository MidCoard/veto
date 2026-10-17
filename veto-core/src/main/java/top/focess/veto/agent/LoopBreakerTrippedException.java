package top.focess.veto.agent;

/** Internal control signal that the current request exhausted its loop budget. */
final class LoopBreakerTrippedException extends RuntimeException {}
