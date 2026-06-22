package top.focess.veto.terminal

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Java-friendly bridge over Mordant's Kotlin API. Detects terminal ANSI capability and downgrades
 * to plain text on dumb terminals so output never shows raw escape sequences. Used by [MordantTheme]
 * to map [top.focess.veto.client.core.StyleToken]s to ANSI strings.
 */
object MordantTerminal {

    @JvmStatic
    fun create(): Terminal = Terminal()

    /** True if the terminal can render ANSI escape codes. */
    @JvmStatic
    fun supportsAnsi(term: Terminal): Boolean =
        term.info.ansiLevel != AnsiLevel.NONE

    // ── Colors (passthrough when ANSI unsupported) ────────────────────────────

    @JvmStatic
    fun red(term: Terminal, text: String): String =
        if (supportsAnsi(term)) TextColors.red(text) else text

    @JvmStatic
    fun cyan(term: Terminal, text: String): String =
        if (supportsAnsi(term)) TextColors.cyan(text) else text

    @JvmStatic
    fun green(term: Terminal, text: String): String =
        if (supportsAnsi(term)) TextColors.green(text) else text

    @JvmStatic
    fun yellow(term: Terminal, text: String): String =
        if (supportsAnsi(term)) TextColors.yellow(text) else text

    // ── Styles ───────────────────────────────────────────────────────────────

    @JvmStatic
    fun bold(term: Terminal, text: String): String =
        if (supportsAnsi(term)) TextStyles.bold(text) else text

    @JvmStatic
    fun dim(term: Terminal, text: String): String =
        if (supportsAnsi(term)) TextStyles.dim(text) else text
}
