/**
 * Host-persisted, plugin-namespaced application, user, and session data.
 *
 * <p>Scope values are host-issued grants rather than caller-chosen identifiers. Stores use revision
 * checks and revalidate scope and plugin admission; persistence does not imply that an old handle
 * remains authorized.
 */
package top.focess.veto.api.plugin.storage;
