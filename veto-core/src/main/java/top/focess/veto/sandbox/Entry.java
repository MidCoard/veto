package top.focess.veto.sandbox;

/** A directory entry returned by {@code list_dir}. */
public record Entry(String name, boolean isDirectory, long size) {}
