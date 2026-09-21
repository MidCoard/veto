package top.focess.veto.plugin.contract;

/**
 * File-content protection. The host invokes it at {@code view_file} capture, replacing secret
 * material with SECRET_REF markers before the file content is rendered or observed.
 */
@FunctionalInterface
public interface FileProtection extends TextProtection {}
