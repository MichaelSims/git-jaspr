package sims.michael.gitjaspr

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.regex.PatternSyntaxException
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX

// region Config key registry

/**
 * Describes a configuration key managed by the `jaspr config` commands. [key] is the kebab-case
 * property name as it appears in `.jaspr.properties` (and as Clikt's value source reads it).
 * [validate] returns an error message for an invalid value, or null when the value is acceptable.
 */
data class ConfigKeySpec(
    val key: String,
    val default: String?,
    val description: String,
    val secret: Boolean = false,
    val validate: (String) -> String? = { null },
)

private val LOG_LEVELS = listOf("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL")

private fun booleanValue(value: String) =
    if (value == "true" || value == "false") null else "must be 'true' or 'false'"

private fun logLevelValue(value: String) =
    if (value.uppercase() in LOG_LEVELS) null else "must be one of ${LOG_LEVELS.joinToString(", ")}"

private fun regexValue(value: String) =
    try {
        Regex(value)
        null
    } catch (e: PatternSyntaxException) {
        "is not a valid regular expression: ${e.message}"
    }

private fun noSlashValue(value: String) =
    if ("/" in value) "must not contain a forward slash (one is appended automatically)" else null

/** Every non-theme configuration key jaspr understands, in the order `config list` prints them. */
val CONFIG_KEYS: List<ConfigKeySpec> = buildList {
    add(
        ConfigKeySpec(
            "github-token",
            default = null,
            description = "GitHub personal access token",
            secret = true,
        )
    )
    add(
        ConfigKeySpec(
            "log-level",
            default = "INFO",
            description = "Log level (${LOG_LEVELS.joinToString(", ")})",
            validate = ::logLevelValue,
        )
    )
    add(
        ConfigKeySpec(
            "log-to-files",
            default = "true",
            description = "Write trace logs to a file",
            validate = ::booleanValue,
        )
    )
    add(
        ConfigKeySpec(
            "logs-directory",
            default = "${System.getProperty("java.io.tmpdir")}/jaspr",
            description = "Directory for trace log files",
        )
    )
    add(
        ConfigKeySpec("remote-name", default = DEFAULT_REMOTE_NAME, description = "Git remote name")
    )
    add(
        ConfigKeySpec(
            "target",
            default = DEFAULT_TARGET_REF,
            description = "Default target branch on the remote",
        )
    )
    add(
        ConfigKeySpec(
            "dont-push-regex",
            default = "^(dont[ -]?push)\\b.*$",
            description =
                "Case-insensitive regex matching commit subjects that should not be pushed",
            validate = ::regexValue,
        )
    )
    add(
        ConfigKeySpec(
            "theme",
            default = "default",
            description = "Terminal theme (default, mono, or a custom name)",
        )
    )
    add(
        ConfigKeySpec(
            "show-tips",
            default = "true",
            description = "Show tips after commands",
            validate = ::booleanValue,
        )
    )
    add(
        ConfigKeySpec(
            "use-fzf",
            default = "true",
            description = "Use fzf for interactive selection when available",
            validate = ::booleanValue,
        )
    )
    add(
        ConfigKeySpec(
            "github-host",
            default = null,
            description = "GitHub host (inferred from the remote URI when unset)",
        )
    )
    add(
        ConfigKeySpec(
            "repo-owner",
            default = null,
            description = "GitHub owner (inferred from the remote URI when unset)",
        )
    )
    add(
        ConfigKeySpec(
            "repo-name",
            default = null,
            description = "GitHub repo name (inferred from the remote URI when unset)",
        )
    )
    add(
        ConfigKeySpec(
            "remote-branch-prefix",
            default = DEFAULT_REMOTE_BRANCH_PREFIX,
            description = "Prefix for remote branches that track commits",
            validate = ::noSlashValue,
        )
    )
    add(
        ConfigKeySpec(
            "remote-named-stack-branch-prefix",
            default = DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX,
            description = "Prefix for remote named-stack branches",
            validate = ::noSlashValue,
        )
    )
    add(
        ConfigKeySpec(
            "update-check-enabled",
            default = "true",
            description = "Periodically check whether a newer jaspr is available",
            validate = ::booleanValue,
        )
    )
}

private val configKeysByName = CONFIG_KEYS.associateBy(ConfigKeySpec::key)

fun configKeySpec(key: String): ConfigKeySpec? = configKeysByName[key]

private val themeKeyRegex =
    Regex("^[a-z0-9][a-z0-9-]*\\.(${THEME_ROLES.joinToString("|")})\\.(color|weight)$")

private val hexColorRegex = Regex("^#?[0-9a-fA-F]{6}$")

fun isThemeKey(key: String) = themeKeyRegex.matches(key)

private fun themeValue(key: String, value: String) =
    when (key.substringAfterLast('.')) {
        "color" -> if (hexColorRegex.matches(value)) null else "must be a hex color (e.g. #FF5733)"
        "weight" -> if (value == "bold" || value == "dim") null else "must be 'bold' or 'dim'"
        else -> null
    }

fun isKnownConfigKey(key: String) = configKeySpec(key) != null || isThemeKey(key)

/** Validates [value] for [key]; returns an error message or null. Assumes [key] is known. */
fun validateConfigValue(key: String, value: String): String? =
    if (isThemeKey(key)) themeValue(key, value) else configKeySpec(key)?.validate?.invoke(value)

/** Suggests the closest known key to [key] for a "did you mean" hint, or null if none is close. */
fun suggestConfigKey(key: String): String? =
    CONFIG_KEYS.map(ConfigKeySpec::key)
        .map { candidate -> candidate to levenshtein(candidate, key) }
        .filter { (_, distance) -> distance <= 3 }
        .minByOrNull { (_, distance) -> distance }
        ?.first

private fun levenshtein(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
        }
        previous = current
    }
    return previous[b.length]
}

// endregion

// region Config file resolution and editing

enum class ConfigSource {
    REPO_FILE,
    HOME_FILE,
    ENV,
    DEFAULT,
    UNSET,
}

data class ResolvedConfigValue(val value: String?, val source: ConfigSource)

/**
 * Resolves effective config values with jaspr's precedence: env override, then per-repo file, then
 * user-wide (home) file, then the registered default. [repoFile] may be null when not in a repo.
 */
class ConfigResolver(private val homeFile: File, private val repoFile: File?) {
    fun resolve(key: String): ResolvedConfigValue {
        envOverride(key)?.let {
            return it
        }
        readValue(repoFile, key)?.let {
            return ResolvedConfigValue(it, ConfigSource.REPO_FILE)
        }
        readValue(homeFile, key)?.let {
            return ResolvedConfigValue(it, ConfigSource.HOME_FILE)
        }
        val default = configKeySpec(key)?.default
        return if (default != null) ResolvedConfigValue(default, ConfigSource.DEFAULT)
        else ResolvedConfigValue(null, ConfigSource.UNSET)
    }

    /** Theme keys present in either config file, sorted for stable display. */
    fun presentThemeKeys(): List<String> =
        buildSet {
                for (file in listOfNotNull(repoFile, homeFile)) {
                    if (file.exists()) addAll(loadProperties(file).stringPropertyNames())
                }
            }
            .filter(::isThemeKey)
            .sorted()

    private fun envOverride(key: String): ResolvedConfigValue? =
        when (key) {
            "github-token" ->
                System.getenv(GITHUB_TOKEN_ENV_VAR)?.takeUnless(String::isEmpty)?.let {
                    ResolvedConfigValue(it, ConfigSource.ENV)
                }
            "update-check-enabled" ->
                System.getenv(JASPR_NO_UPDATE_CHECK_ENV_VAR)?.takeUnless(String::isEmpty)?.let {
                    ResolvedConfigValue("false", ConfigSource.ENV)
                }
            else -> null
        }
}

private fun readValue(file: File?, key: String): String? =
    file?.takeIf(File::exists)?.let { loadProperties(it).getProperty(key) }

private fun loadProperties(file: File): Properties =
    Properties().apply { file.reader().use(::load) }

/**
 * Edits `.jaspr.properties` files while preserving comments and key ordering. `set` updates an
 * existing active line in place or appends a new one; `unset` removes it. Writes are atomic (temp
 * file in the same directory, then rename) so a killed process can't corrupt the config.
 */
object ConfigFileEditor {
    fun setValue(file: File, key: String, value: String) {
        val newLine = "$key=${escape(value)}"
        if (!file.exists() || file.readText().isBlank()) {
            writeAtomically(file, newLine + "\n")
            return
        }
        val lines = file.readText().removeSuffix("\n").split("\n").toMutableList()
        val index = lines.indexOfFirst { lineKey(it) == key }
        if (index >= 0) lines[index] = newLine else lines.add(newLine)
        writeAtomically(file, lines.joinToString("\n") + "\n")
    }

    /** Removes [key] from [file]. Returns true if a line was removed. */
    fun unsetValue(file: File, key: String): Boolean {
        if (!file.exists()) return false
        val lines = file.readText().removeSuffix("\n").split("\n")
        val kept = lines.filterNot { lineKey(it) == key }
        if (kept.size == lines.size) return false
        writeAtomically(file, if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        return true
    }

    /** The property key an active (uncommented) line declares, or null for blanks and comments. */
    private fun lineKey(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null
        val separator = trimmed.indexOfFirst { it == '=' || it == ':' }
        return (if (separator >= 0) trimmed.substring(0, separator) else trimmed).trim()
    }

    // Minimal java.util.Properties value escaping. Backslash matters most: dont-push-regex and
    // similar values contain `\b`, which would be mangled on read without escaping the backslash.
    private fun escape(value: String) = buildString {
        for ((index, ch) in value.withIndex()) {
            when (ch) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                ' ' if index == 0 -> append("\\ ")
                else -> append(ch)
            }
        }
    }

    private fun writeAtomically(file: File, content: String) {
        val directory = file.absoluteFile.parentFile
        directory.mkdirs()
        val temp = File.createTempFile(".jaspr-config", ".tmp", directory)
        temp.writeText(content)
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

// endregion

// region Commands

class ConfigCommand : SuspendingCliktCommand(name = "config") {
    override fun help(context: Context) = "Get and set jaspr configuration"

    override suspend fun run() = Unit
}

/**
 * Shared plumbing for the `config` subcommands. Deliberately avoids [AppWiring] so these commands
 * work before a token or repo is fully configured.
 */
abstract class ConfigSubcommand(name: String) : SuspendingCliktCommand(name = name) {
    protected val cliContext by requireObject<CliContext>()
    protected val renderer
        get() = cliContext.renderer

    protected val theme
        get() = cliContext.theme

    protected val homeConfigFile
        get() = File(System.getenv("HOME")).resolve(CONFIG_FILE_NAME)

    protected val repoConfigFile
        get() = cliContext.workingDirectory.resolve(CONFIG_FILE_NAME)

    protected fun resolver() = ConfigResolver(homeConfigFile, repoConfigFile)

    protected fun requireKnownKey(key: String) {
        if (!isKnownConfigKey(key)) {
            val suggestion = suggestConfigKey(key)?.let { " Did you mean '$it'?" }.orEmpty()
            throw UsageError(
                "unknown config key '$key'.$suggestion Run `jaspr config list` to see all keys."
            )
        }
    }

    protected fun sourceLabel(source: ConfigSource) =
        when (source) {
            ConfigSource.REPO_FILE -> "repo"
            ConfigSource.HOME_FILE -> "home"
            ConfigSource.ENV -> "env"
            ConfigSource.DEFAULT -> "default"
            ConfigSource.UNSET -> "unset"
        }
}

class ConfigGet : ConfigSubcommand(name = "get") {
    override fun help(context: Context) = "Print the effective value of a config key and its source"

    private val key by argument("KEY").help("Configuration key")

    override suspend fun run() {
        requireKnownKey(key)
        val resolved = resolver().resolve(key)
        if (resolved.value == null) {
            renderer.info { "${entity(key)} is not set." }
        } else {
            renderer.info {
                "${entity(key)} = ${value(resolved.value)} ${muted("(${sourceLabel(resolved.source)})")}"
            }
        }
    }
}

class ConfigSet : ConfigSubcommand(name = "set") {
    override fun help(context: Context) = "Write a config key to the user-wide or repo config file"

    private val repo by
        option("--repo", "--local")
            .flag()
            .help("Write to the repo config (./$CONFIG_FILE_NAME) instead of ~/$CONFIG_FILE_NAME")

    private val key by argument("KEY").help("Configuration key")
    private val value by argument("VALUE").help("Value to set")

    override suspend fun run() {
        requireKnownKey(key)
        validateConfigValue(key, value)?.let { error ->
            throw UsageError("invalid value for '$key': $error")
        }
        val target = if (repo) repoConfigFile else homeConfigFile
        ConfigFileEditor.setValue(target, key, value)
        renderer.info { "Set ${entity(key)} in ${value(target.path)}" }
    }
}

class ConfigUnset : ConfigSubcommand(name = "unset") {
    override fun help(context: Context) = "Remove a config key (reverting it to its default)"

    private val repo by
        option("--repo", "--local")
            .flag()
            .help("Edit the repo config (./$CONFIG_FILE_NAME) instead of ~/$CONFIG_FILE_NAME")

    private val key by argument("KEY").help("Configuration key")

    override suspend fun run() {
        requireKnownKey(key)
        val target = if (repo) repoConfigFile else homeConfigFile
        if (ConfigFileEditor.unsetValue(target, key)) {
            renderer.info { "Unset ${entity(key)} in ${value(target.path)}" }
        } else {
            renderer.info { "${entity(key)} was not set in ${value(target.path)}" }
        }
    }
}

class ConfigList : ConfigSubcommand(name = "list") {
    override fun help(context: Context) =
        "List all config keys with their effective values, source, and a * on non-default values"

    override suspend fun run() {
        val resolver = resolver()
        for ((key, _, _, secret) in CONFIG_KEYS) {
            val resolved = resolver.resolve(key)
            renderer.info {
                val shown =
                    when {
                        resolved.value == null -> muted("(unset)")
                        secret -> muted("***")
                        else -> value(resolved.value)
                    }
                val marker = if (resolved.source in DERIVED_SOURCES) "" else " *"
                "$key = $shown ${muted("[${sourceLabel(resolved.source)}]")}$marker"
            }
        }
        val themeKeys = resolver.presentThemeKeys()
        if (themeKeys.isNotEmpty()) {
            renderer.info { "" }
            renderer.info { heading("Custom theme keys:") }
            for (key in themeKeys) {
                val resolved = resolver.resolve(key)
                renderer.info {
                    "$key = ${value(resolved.value.orEmpty())} ${muted("[${sourceLabel(resolved.source)}]")}"
                }
            }
        }
    }

    private companion object {
        val DERIVED_SOURCES = setOf(ConfigSource.DEFAULT, ConfigSource.UNSET)
    }
}

// endregion
