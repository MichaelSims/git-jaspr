package sims.michael.gitjaspr

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim

/**
 * Maps semantic UI roles to styling functions, enabling color scheme switching and monochrome
 * output for testing.
 */
interface Theme {
    /** Error messages and invalid states. */
    fun error(text: String): String

    /** Success messages and positive outcomes. */
    fun success(text: String): String

    /** Abort/cancel messages. */
    fun warning(text: String): String

    /** Entity names: branches, stacks, keys, paths. */
    fun highlight(text: String): String

    /** Supplemental info: commit messages. */
    fun secondary(text: String): String

    /** Section headings. */
    fun heading(text: String): String

    /** Interactive keys, option names, command references. */
    fun emphasis(text: String): String

    /** De-emphasized: defaults, comments. */
    fun muted(text: String): String

    /** Config values, literal data. */
    fun value(text: String): String
}

/** Selects a built-in [Theme] by name. */
enum class ColorScheme {
    DARK,
    LIGHT,
    MONO;

    /** Returns the [Theme] corresponding to this color scheme. */
    fun toTheme(): Theme =
        when (this) {
            DARK -> DarkTheme
            LIGHT -> LightTheme
            MONO -> MonoTheme
        }
}

object DarkTheme : Theme {
    override fun error(text: String) = red(text)

    override fun success(text: String) = green(text)

    override fun warning(text: String) = yellow(text)

    override fun highlight(text: String) = cyan(text)

    override fun secondary(text: String) = brightWhite(text)

    override fun heading(text: String) = bold(text)

    override fun emphasis(text: String) = bold(text)

    override fun muted(text: String) = dim(text)

    override fun value(text: String) = green(text)
}

object LightTheme : Theme {
    override fun error(text: String) = red(text)

    override fun success(text: String) = green(text)

    override fun warning(text: String) = yellow(text)

    override fun highlight(text: String) = blue(text)

    override fun secondary(text: String) = gray(text)

    override fun heading(text: String) = bold(text)

    override fun emphasis(text: String) = bold(text)

    override fun muted(text: String) = dim(text)

    override fun value(text: String) = green(text)
}

object MonoTheme : Theme {
    override fun error(text: String) = text

    override fun success(text: String) = text

    override fun warning(text: String) = text

    override fun highlight(text: String) = text

    override fun secondary(text: String) = text

    override fun heading(text: String) = bold(text)

    override fun emphasis(text: String) = bold(text)

    override fun muted(text: String) = text

    override fun value(text: String) = text
}
