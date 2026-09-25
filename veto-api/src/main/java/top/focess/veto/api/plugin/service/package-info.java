/**
 * Named, major-versioned JSON protocols for plugin-to-plugin calls.
 *
 * <p>Providers register during initialization. Consumers discover providers at start or later,
 * after the host binds the directory. Handles recheck caller and provider admission on every call.
 */
package top.focess.veto.api.plugin.service;
