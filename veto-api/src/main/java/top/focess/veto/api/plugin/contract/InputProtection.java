package top.focess.veto.api.plugin.contract;

/**
 * User-prompt protection. The host invokes it on the user's prompt before the prompt enters session
 * history, so stored history never carries raw secret material.
 */
@FunctionalInterface
public interface InputProtection extends TextProtection {}
