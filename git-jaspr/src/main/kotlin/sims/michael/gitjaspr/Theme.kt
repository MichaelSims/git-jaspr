package sims.michael.gitjaspr

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim

/**
 * Maps functional UI roles to styling functions, enabling color scheme switching and monochrome
 * output for testing.
 */
interface Theme {
    /** Error messages and invalid states. */
    fun error(text: String): String

    /** Success messages and positive outcomes. */
    fun success(text: String): String

    /** Abort/cancel messages and cautionary notices. */
    fun warning(text: String): String

    /** Section headings. */
    fun heading(text: String): String

    /** Named things: branch names, stack names, config keys, file paths. */
    fun entity(text: String): String

    /** Permalinks and URLs. */
    fun url(text: String): String

    /** Inline command references and CLI option names. */
    fun command(text: String): String

    /** Interactive selection keys and numbered list markers. */
    fun keyHint(text: String): String

    /** Commit short messages shown as supplemental context. */
    fun commitSubject(text: String): String

    /** Commit hashes. */
    fun hash(text: String): String

    /** Config file comments and default-value annotations. */
    fun comment(text: String): String

    /** De-emphasized empty-state messages and decorative legends. */
    fun muted(text: String): String

    /** Literal config values. */
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

    override fun heading(text: String) = bold(text)

    override fun entity(text: String) = cyan(text)

    override fun url(text: String) = cyan(text)

    override fun command(text: String) = bold(text)

    override fun keyHint(text: String) = bold(text)

    override fun commitSubject(text: String) = brightWhite(text)

    override fun hash(text: String) = dim(text)

    override fun comment(text: String) = dim(text)

    override fun muted(text: String) = dim(text)

    override fun value(text: String) = green(text)
}

object LightTheme : Theme {
    override fun error(text: String) = red(text)

    override fun success(text: String) = green(text)

    override fun warning(text: String) = yellow(text)

    override fun heading(text: String) = bold(text)

    override fun entity(text: String) = blue(text)

    override fun url(text: String) = blue(text)

    override fun command(text: String) = bold(text)

    override fun keyHint(text: String) = bold(text)

    override fun commitSubject(text: String) = gray(text)

    override fun hash(text: String) = dim(text)

    override fun comment(text: String) = dim(text)

    override fun muted(text: String) = dim(text)

    override fun value(text: String) = green(text)
}

object MonoTheme : Theme {
    override fun error(text: String) = text

    override fun success(text: String) = text

    override fun warning(text: String) = text

    override fun heading(text: String) = text

    override fun entity(text: String) = text

    override fun url(text: String) = text

    override fun command(text: String) = text

    override fun keyHint(text: String) = text

    override fun commitSubject(text: String) = text

    override fun hash(text: String) = text

    override fun comment(text: String) = text

    override fun muted(text: String) = text

    override fun value(text: String) = text
}
