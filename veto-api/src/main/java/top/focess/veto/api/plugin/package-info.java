/**
 * Trusted in-process plugin lifecycle and host binding contracts.
 *
 * <p>Contributions publish implementations but grant no authority. {@link
 * top.focess.veto.api.plugin.PluginContext#service(Class)} exposes optional host-granted Java
 * capabilities, while {@link top.focess.veto.api.plugin.PluginContext#services()} exposes named
 * JSON protocols implemented by plugins. Neither boundary sandboxes arbitrary Java code.
 */
package top.focess.veto.api.plugin;
