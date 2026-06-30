package top.focess.veto.sandbox;

import org.jspecify.annotations.NonNull;

/** A directory entry returned by {@code list_dir}. */
public record Entry(@NonNull String name, boolean isDirectory, long size) {}
