package top.focess.veto.terminal

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Java-friendly bridge over Mordant's Kotlin API. Detects terminal ANSI capability and
 * downgrades to plain text on dumb terminals so output never shows raw escape sequences.
 */
object MordantTerminal {

    @JvmStatic
    fun create(): Terminal = Terminal()

    /** True if the terminal can render ANSI escape codes. */
    @JvmStatic
    fun supportsAnsi(term: Terminal): Boolean =
        term.info.ansiLevel != AnsiLevel.NONE

    // ── Output ───────────────────────────────────────────────────────────────

    @JvmStatic
    fun println(term: Terminal, text: Any?) {
        term.println(text)
    }

    @JvmStatic
    fun print(term: Terminal, text: Any?) {
        term.print(text)
    }

    /** Flush any buffered output. Call after a series of {@link #print} calls. */
    @JvmStatic
    fun flush(term: Terminal) {
        @Suppress(" SwallowedException")
        runCatching { term.println() }
    }

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

    // ── Table ────────────────────────────────────────────────────────────────

    @JvmStatic
    fun table(term: Terminal, headers: List<String>, rows: List<List<String>>): String {
        if (!supportsAnsi(term)) return plainTable(headers, rows)
        return term.render(table {
            header { row(*headers.toTypedArray()) }
            body { for (r in rows) row(*r.toTypedArray()) }
        })
    }

    private fun plainTable(headers: List<String>, rows: List<List<String>>): String {
        val cols = headers.size
        val widths = IntArray(cols) { c ->
            maxOf(headers[c].length, rows.maxOfOrNull { it.getOrElse(c) { "" }.length } ?: 0)
        }

        fun buildRow(values: List<String>) = values.mapIndexed { c, v ->
            " %-${widths[c]}s ".format(v)
        }.joinToString("").trimEnd()

        val sb = StringBuilder()
        sb.appendLine(buildRow(headers))
        sb.appendLine(widths.joinToString("+") { "-".repeat(it + 2) })
        for (r in rows) sb.appendLine(buildRow(r))
        return sb.toString()
    }
}
