package top.focess.veto.plugin.contract;

/**
 * View-file observation protection. The host invokes it on the {@code view_file} observation before
 * the observation is committed to history, masking plain segments while preserving SECRET_REF
 * markers produced by {@link FileProtection}.
 */
@FunctionalInterface
public interface FileObservation extends TextProtection {}
