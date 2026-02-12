package sims.michael.gitjaspr

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import java.util.Properties
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Maps functional UI roles to styling functions, enabling color scheme switching and monochrome
 * output for testing.
 */
interface Theme {
    /** The name of this theme */
    val name: String

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

    companion object {
        val logger: Logger = LoggerFactory.getLogger(Theme::class.java)
    }
}

/** The role names which can be customized in a theme, in declaration order. */
val THEME_ROLES =
    listOf(
        "error",
        "success",
        "warning",
        "heading",
        "entity",
        "url",
        "command",
        "keyHint",
        "commitSubject",
        "hash",
        "comment",
        "muted",
        "value",
    )

/**
 * Resolves a [Theme] by name. Built-in names (`default`, `mono`) return singleton themes. Any other
 * name is treated as a custom scheme whose role overrides are read from [properties] using keys of
 * the form `<name>.<role>.color` (hex RGB) and `<name>.<role>.weight` (`bold` or `dim`). Roles
 * without overrides fall back to [DefaultTheme].
 */
fun resolveTheme(name: String, properties: Properties): Theme {
    Theme.logger.trace("resolveTheme {}", name)
    return when (name) {
        "default" -> DefaultTheme
        "mono" -> MonoTheme
        else -> buildCustomTheme(name, properties)
    }
}

object DefaultTheme : Theme {
    override val name = "default"

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

object MonoTheme : Theme {
    override val name = "mono"

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

/**
 * Builds a [Theme] by reading `<scheme>.<role>.color` and `<scheme>.<role>.weight` keys from
 * [properties]. Roles without any overrides delegate to [DefaultTheme].
 */
private fun buildCustomTheme(scheme: String, properties: Properties): Theme {
    val logger = Theme.logger
    logger.trace("buildCustomTheme scheme={}", scheme)
    val schemeKeys = properties.keys.filter { key -> (key as String).startsWith("$scheme.") }
    logger.trace("Found {} properties matching '{}.': {}", schemeKeys.size, scheme, schemeKeys)
    val styleFunctions = mutableMapOf<String, (String) -> String>()
    for (role in THEME_ROLES) {
        val colorHex = properties.getProperty("$scheme.$role.color")
        val weight = properties.getProperty("$scheme.$role.weight")
        if (colorHex == null && weight == null) continue
        logger.trace("Role '{}': color={}, weight={}", role, colorHex, weight)
        val style = buildRoleStyle(colorHex, weight, scheme, role)
        styleFunctions[role] = style
    }
    if (styleFunctions.isEmpty()) {
        logger.trace("No role overrides found for '{}', falling back to DefaultTheme", scheme)
        return DefaultTheme
    }
    logger.trace(
        "Built CustomTheme with {} role overrides: {}",
        styleFunctions.size,
        styleFunctions.keys,
    )
    return CustomTheme(scheme, styleFunctions)
}

/**
 * Builds a styling function from an optional hex color and optional weight.
 *
 * @throws IllegalArgumentException if [colorHex] is not a valid hex color or [weight] is not `bold`
 *   or `dim`.
 */
private fun buildRoleStyle(
    colorHex: String?,
    weight: String?,
    scheme: String,
    role: String,
): (String) -> String {
    val colorFn: ((String) -> String)? =
        colorHex?.let { hex ->
            runCatching { TextColors.rgb(hex) }
                .getOrElse {
                    throw IllegalArgumentException(
                        "Invalid color '$hex' for $scheme.$role.color " +
                            "(expected hex like #FF5733 or FF5733)"
                    )
                }
                .let { style -> { text: String -> style(text) } }
        }
    val weightFn: ((String) -> String)? =
        when (weight?.lowercase()) {
            "bold" -> { text: String -> bold(text) }
            "dim" -> { text: String -> dim(text) }
            null -> null
            else ->
                throw IllegalArgumentException(
                    "Invalid weight '$weight' for $scheme.$role.weight (expected 'bold' or 'dim')"
                )
        }
    val fns = listOfNotNull(colorFn, weightFn)
    return { text -> fns.fold(text) { acc, fn -> fn(acc) } }
}

/**
 * A [Theme] backed by a map of role-name to styling function, falling back to [DefaultTheme] for
 * any role not present in the map.
 */
private class CustomTheme(
    override val name: String,
    private val styles: Map<String, (String) -> String>,
    private val fallback: Theme = DefaultTheme,
) : Theme {
    override fun error(text: String) = apply("error", text, fallback::error)

    override fun success(text: String) = apply("success", text, fallback::success)

    override fun warning(text: String) = apply("warning", text, fallback::warning)

    override fun heading(text: String) = apply("heading", text, fallback::heading)

    override fun entity(text: String) = apply("entity", text, fallback::entity)

    override fun url(text: String) = apply("url", text, fallback::url)

    override fun command(text: String) = apply("command", text, fallback::command)

    override fun keyHint(text: String) = apply("keyHint", text, fallback::keyHint)

    override fun commitSubject(text: String) = apply("commitSubject", text, fallback::commitSubject)

    override fun hash(text: String) = apply("hash", text, fallback::hash)

    override fun comment(text: String) = apply("comment", text, fallback::comment)

    override fun muted(text: String) = apply("muted", text, fallback::muted)

    override fun value(text: String) = apply("value", text, fallback::value)

    private fun apply(role: String, text: String, fallback: (String) -> String) =
        (styles[role] ?: fallback)(text)
}
