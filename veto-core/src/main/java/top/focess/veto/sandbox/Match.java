package top.focess.veto.sandbox;

/** A grep match returned by {@code grep_search}. */
public record Match(String file, int line, String text) {}
