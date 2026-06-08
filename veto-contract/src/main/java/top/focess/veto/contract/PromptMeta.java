package top.focess.veto.contract;

import java.util.Map;

/**
 * Typed metadata for {@link IpcFrame.Prompt} frames — replaces ad-hoc {@code Map<String, Object>}
 * with a structured record so that the {@code mask} field is a compiler-checked boolean instead of
 * a {@code Boolean.TRUE.equals(meta.get("mask"))} cast at every call site.
 *
 * @param text the prompt label shown above the input field
 * @param mask whether the terminal should mask input characters
 */
public record PromptMeta(String text, boolean mask) {

    /** A simple unmasked prompt. */
    public static PromptMeta simple(String text) {
        return new PromptMeta(text, false);
    }

    /** A masked prompt (e.g. for passwords or API keys). */
    public static PromptMeta masked(String text) {
        return new PromptMeta(text, true);
    }

    /** Convert to the legacy {@code Map} form for {@link IpcFrame.Prompt} compatibility. */
    public Map<String, Object> toMeta() {
        return Map.of(IpcMeta.PROMPT, text, IpcMeta.MASK, mask);
    }
}
