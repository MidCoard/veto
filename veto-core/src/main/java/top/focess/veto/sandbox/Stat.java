package top.focess.veto.sandbox;

/** A stat result for a path (used by ReadHistory snapshots). */
public record Stat(boolean exists, long size, boolean isDirectory) {}
