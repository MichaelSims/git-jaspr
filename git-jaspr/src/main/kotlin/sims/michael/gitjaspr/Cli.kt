package sims.michael.gitjaspr

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Level.*
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.core.installMordantMarkdown
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.sources.ChainedValueSource
import com.github.ajalt.clikt.sources.PropertiesValueSource
import com.github.ajalt.clikt.sources.ValueSource.Companion.getKey
import com.github.ajalt.mordant.terminal.prompt
import java.io.File
import java.lang.reflect.Proxy
import java.time.ZonedDateTime
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteNamedStackRef

// region Option Groups

class TargetRefOptions : OptionGroup() {
    val target by
        option("-t", "--target", completionCandidates = remoteBranchCandidates)
            .default(DEFAULT_TARGET_REF)
            .help { "Target branch on the remote" }
    val local by
        option("-l", "--local").default(DEFAULT_LOCAL_OBJECT).help {
            "Local branch or commit that is the HEAD of the current stack"
        }
    val refSpec
        get() = RefSpec(local, target)
}

class TargetOptions : OptionGroup() {
    val target by
        option("-t", "--target", completionCandidates = remoteBranchCandidates)
            .default(DEFAULT_TARGET_REF)
            .help { "Target branch on the remote" }
}

class CleanBehaviorOptions : OptionGroup() {
    val cleanAbandonedPrs by
        option().flag("--ignore-abandoned-prs", default = true).help {
            "Also close open PRs for orphaned jaspr branches"
        }
    val cleanAllCommits by
        option().flag("--clean-only-my-commits", default = false).help {
            "Remove branches regardless of commit author"
        }
}

// endregion

// region Root Command

class CliContext(
    val theme: Theme,
    val renderer: Renderer,
    val tipProvider: TipProvider?,
    val logFilePath: String?,
    val useFzf: Boolean,
    appWiringFactory: () -> AppWiring,
) {
    val appWiring by lazy(appWiringFactory)
}

/**
 * Root command that owns infrastructure options and passes [CliContext] to subcommands via context.
 * Subcommands access it via `requireObject<CliContext>()`.
 */
class GitJasprRoot : SuspendingCliktCommand(name = "jaspr") {
    override fun helpEpilog(context: Context) = helpEpilog

    private val workingDirectory =
        File(System.getProperty(WORKING_DIR_PROPERTY_NAME) ?: ".")
            .findNearestGitDir()
            .canonicalFile
            .also { dir ->
                require(dir.exists()) { "${dir.absolutePath} does not exist" }
                require(dir.isDirectory) { "${dir.absolutePath} is not a directory" }
            }

    init {
        context {
            valueSource =
                ChainedValueSource(
                    listOf(workingDirectory, File(System.getenv("HOME"))).map { dir ->
                        PropertiesValueSource.from(
                            dir.resolve(CONFIG_FILE_NAME),
                            getKey = getKey(joinSubcommands = null),
                        )
                    }
                )
            helpFormatter = { MordantHelpFormatter(context = it, showDefaultValues = true) }
        }
    }

    private val missingTokenMessage =
        """
Hello! First time running Jaspr?

We couldn't find your GitHub PAT (personal access token).
Run 'jaspr init' to generate a config file, then edit
~/$CONFIG_FILE_NAME and replace the placeholder token
with a real one (read:org, read:user, repo, user:email).

Alternatively, set the environment variable $GITHUB_TOKEN_ENV_VAR.

NOTE: Please remember to enable SSO on your token if
applicable.
    """
            .trimIndent()

    private val dontPushRegex by
        option().default("^(dont[ -]?push)\\b.*$").help {
            "Regular expression pattern (case-insensitive) to match commit subjects that should not be pushed."
        }

    private val showTips by
        option().flag("--no-show-tips", default = true).help { "Show tips after commands" }

    private val remoteName by
        option(
                "-r",
                "--remote-name",
                completionCandidates = CompletionCandidates.Custom.fromStdout("git remote"),
            )
            .help { "Git remote name" }
            .default(DEFAULT_REMOTE_NAME)

    private val githubToken by
        option(envvar = GITHUB_TOKEN_ENV_VAR, hidden = true)
            .transformAll(showAsRequired = false) { stringList -> stringList.lastOrNull() }
            .help { "GitHub personal access token (or set $GITHUB_TOKEN_ENV_VAR)" }

    private val gitHubOptions by GitHubOptions()

    val logLevel: Level by
        option("-L", "--log-level")
            .choice(
                *listOf(OFF, ERROR, WARN, INFO, DEBUG, TRACE, ALL)
                    .map { level -> level.levelStr to level }
                    .toTypedArray(),
                ignoreCase = true,
            )
            .default(INFO)
            .help { "Log level" }

    private val logToFilesDelegate: OptionWithValues<Boolean, Boolean, Boolean> =
        option(hidden = true).flag("--no-log-to-files", default = true).help {
            "Write trace logs to directory specified by the ${logsDirectoryDelegate.names.first()} option"
        }

    private val logsDirectoryDelegate: OptionWithValues<File, File, File> =
        option(hidden = true)
            .file()
            .default(File("${System.getProperty("java.io.tmpdir")}/jaspr"))
            .help {
                "Trace logs will be written into this directory if ${logToFilesDelegate.names.first()} is enabled"
            }

    private val logToFiles: Boolean by logToFilesDelegate
    private val logsDirectory: File by logsDirectoryDelegate

    private val useFzf by
        option().flag("--no-fzf", default = true).help {
            "Use fzf for interactive selection when available"
        }

    private val remoteBranchPrefix by
        option(hidden = true)
            .default(DEFAULT_REMOTE_BRANCH_PREFIX)
            .help {
                "The prefix to use when encoding unique commit IDs into remote ref names " +
                    "(example: $DEFAULT_REMOTE_BRANCH_PREFIX)"
            }
            .validate { value ->
                if (value.contains("/")) {
                    fail(
                        "The remote branch prefix should not contain a forward slash; one will be appended automatically"
                    )
                }
            }

    private val remoteNamedStackBranchPrefix by
        option(hidden = true)
            .default(DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX)
            .help {
                "The prefix to use when pushing named stacks (example: $DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX)"
            }
            .validate { value ->
                if (value.contains("/")) {
                    fail(
                        "The remote named stack branch prefix should not contain a forward slash; one will be appended " +
                            "automatically"
                    )
                }
                if (value == remoteBranchPrefix) {
                    fail(
                        "The remote named stack branch prefix should not be the same as the remote branch prefix"
                    )
                }
            }

    private val showConfig by
        option(hidden = true).flag("--no-show-config", default = false).help {
            "Print the effective configuration to standard output (for debugging)"
        }

    private val updateCheckEnabled by
        option().flag("--no-update-check", default = true).help {
            "Periodically check whether a newer jaspr is available and surface a one-line notice. " +
                "Disable persistently by setting `update-check.enabled=false` in " +
                "~/$CONFIG_FILE_NAME, or for a single shell with " +
                "$JASPR_NO_UPDATE_CHECK_ENV_VAR=1."
        }

    private val theme by
        option(
                "--theme",
                completionCandidates = CompletionCandidates.Fixed(setOf("default", "mono")),
            )
            .default("default")
            .help { "Terminal theme (default, mono, or a custom name)" }

    private fun buildAppWiring(renderer: Renderer): AppWiring {
        val token =
            githubToken
                ?: run {
                    renderer.error { missingTokenMessage }
                    throw ProgramResult(1)
                }
        val gitClient = DefaultGitClient(workingDirectory, remoteBranchPrefix)
        val githubInfo = determineGithubInfo(gitClient)
        val config =
            Config(
                workingDirectory,
                remoteName,
                githubInfo,
                remoteBranchPrefix,
                remoteNamedStackBranchPrefix,
                logLevel,
                logsDirectory.takeIf { logToFiles },
                dontPushRegex,
                showTips,
            )
        return DefaultAppWiring(
            githubToken = token,
            config = config,
            gitClient = gitClient,
            renderer = renderer,
            updateCheckSkip = ::isUpdateCheckSkipped,
        )
    }

    /**
     * Predicate composing all reasons to skip the update check:
     * - `--no-update-check` (or `update-check.enabled=false` in `~/$CONFIG_FILE_NAME` via the same
     *   Clikt value source as other flags).
     * - `$JASPR_NO_UPDATE_CHECK_ENV_VAR` env var set to any non-empty value.
     * - Running under CI (any of the well-known CI env vars set).
     * - Stdout not connected to a TTY — the notice would just become noise in piped output or
     *   redirected logs.
     */
    private fun isUpdateCheckSkipped(): Boolean =
        !updateCheckEnabled ||
            !System.getenv(JASPR_NO_UPDATE_CHECK_ENV_VAR).isNullOrEmpty() ||
            CI_ENV_VARS.any { !System.getenv(it).isNullOrEmpty() } ||
            System.console() == null

    private fun determineGithubInfo(gitClient: GitClient): GitHubInfo {
        val host = gitHubOptions.githubHost
        val owner = gitHubOptions.repoOwner
        val name = gitHubOptions.repoName
        return if (host != null && owner != null && name != null) {
            GitHubInfo(host, owner, name)
        } else {
            val remoteUri =
                requireNotNull(gitClient.getRemoteUriOrNull(remoteName)) {
                    buildString {
                        appendLine("Couldn't find remote $remoteName.")
                        if (remoteName == DEFAULT_REMOTE_NAME) {
                            append(
                                "Please specify which remote to use with the --remote-name option (see --help)."
                            )
                        } else {
                            append("The name you specified doesn't seem to exist.")
                        }
                    }
                }
            val fromUri =
                requireNotNull(extractGitHubInfoFromUri(remoteUri)) {
                    "Couldn't infer github info from $remoteName URI: $remoteUri. \n" +
                        "You can specify the information I need manually with --github-host, --repo-owner, " +
                        "and --repo-name."
                }
            GitHubInfo(host ?: fromUri.host, owner ?: fromUri.owner, name ?: fromUri.name)
        }
    }

    private fun migrateOldConfigIfNeeded(directory: File) {
        val newConfig = directory.resolve(CONFIG_FILE_NAME)
        val oldConfig = directory.resolve(OLD_CONFIG_FILE_NAME)
        if (!newConfig.exists() && oldConfig.exists()) {
            newConfig.writeText(migrateConfig(oldConfig))
            echo("Migrated config: ${oldConfig.absolutePath} -> ${newConfig.absolutePath}")
        }
    }

    /** Loads config properties from both file locations, per-repo values taking precedence. */
    private fun loadThemeProperties(): Properties {
        Cli.logger.trace("loadThemeProperties")
        val userWide = File(System.getenv("HOME")).resolve(CONFIG_FILE_NAME)
        val perRepo = workingDirectory.resolve(CONFIG_FILE_NAME)
        val props = Properties()
        for (file in listOf(userWide, perRepo)) {
            if (file.exists()) file.reader().use { props.load(it) }
        }
        return props
    }

    override suspend fun run() {
        val logger = Cli.logger
        val (loggingContext, logFilePath) =
            initLogging(logLevel, logsDirectory.takeIf { logToFiles })
        if (currentContext.invokedSubcommand !is Init) {
            listOf(File(System.getenv("HOME")), workingDirectory)
                .forEach(::migrateOldConfigIfNeeded)
        }
        val themeProperties = loadThemeProperties()
        logger.trace("Resolving theme '{}'", theme)
        val resolvedTheme = resolveTheme(theme, themeProperties)
        logger.trace("Resolved theme: {}", resolvedTheme::class.simpleName)
        val renderer = ConsoleRenderer(resolvedTheme)
        if (showConfig) {
            buildAppWiring(renderer).use { appWiring ->
                echo(appWiring.json.encodeToString(appWiring.config))
                throw ProgramResult(0)
            }
        }
        currentContext.obj =
            CliContext(
                resolvedTheme,
                renderer,
                tipProvider = if (showTips) TipProvider() else null,
                logFilePath = logFilePath,
                useFzf = useFzf,
            ) {
                try {
                    buildAppWiring(renderer)
                } catch (e: Exception) {
                    logger.debug("Initialization failed", e)
                    loggingContext.stop()
                    renderer.error { e.message.orEmpty() }
                    throw ProgramResult(255)
                }
            }
        logger.debug("{} version {}", GitJaspr::class.java.simpleName, VERSION)
    }

    private fun initLogging(
        logLevel: Level,
        logFileDirectory: File?,
    ): Pair<LoggerContext, String?> {
        // NOTE: There is an initial "bootstrap" logging config set via logback.xml. This code makes
        // assumptions based on configuration in that file.
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        val fileAppender =
            if (logFileDirectory != null) createFileAppender(loggerContext, logFileDirectory)
            else null

        rootLogger.getAppender("STDOUT").apply {
            clearAllFilters()
            addFilter(
                ThresholdFilter().apply {
                    setLevel(logLevel.levelStr)
                    start()
                }
            )
        }

        if (fileAppender != null) {
            rootLogger.addAppender(fileAppender)
            Cli.logger.debug("Logging to {}", fileAppender.file)
        }

        // Configure the dedicated UserOutput logger used by ConsoleRenderer.
        // Set additivity=false so messages only go to the FILE appender (not STDOUT),
        // preventing duplication since ConsoleRenderer already writes to the console directly.
        loggerContext.getLogger(ConsoleRenderer.FILE_LOGGER_NAME).apply {
            isAdditive = false
            level = ALL
            if (fileAppender != null) {
                addAppender(fileAppender)
            }
        }

        return loggerContext to fileAppender?.file
    }

    private fun createFileAppender(
        loggerContext: LoggerContext,
        directory: File,
    ): FileAppender<ILoggingEvent> =
        RollingFileAppender<ILoggingEvent>().apply {
            val fileAppender = this
            context = loggerContext
            name = "FILE"
            encoder =
                PatternLayoutEncoder().apply {
                    context = loggerContext
                    pattern = "%d{YYYY-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{5} - %msg%n"
                    start()
                }
            rollingPolicy =
                TimeBasedRollingPolicy<ILoggingEvent>().apply {
                    context = loggerContext
                    setParent(fileAppender)
                    fileNamePattern = "${directory.absolutePath}/jaspr.%d.log.txt"
                    maxHistory = 7
                    isCleanHistoryOnStart = true
                    start()
                }
            addFilter(
                ThresholdFilter().apply {
                    setLevel(TRACE.levelStr)
                    start()
                }
            )
            start()
        }
}

private class GitHubOptions : OptionGroup() {
    val githubHost by option().help { "GitHub host (inferred from remote URI)" }
    val repoOwner by option().help { "GitHub owner name (inferred from remote URI)" }
    val repoName by option().help { "GitHub repo name (inferred from remote URI)" }
}

// endregion

// region Subcommand Base Class

/**
 * Thin base class for subcommands. Accesses [CliContext] from the parent command's context.
 * Subclasses implement [doRun] as a suspend function.
 */
abstract class GitJasprSubcommand(
    name: String? = null,
    private val helpText: String = "",
    private val isHidden: Boolean = false,
) : SuspendingCliktCommand(name = name) {

    override fun help(context: Context) = helpText

    override fun helpEpilog(context: Context) = helpEpilog

    override val hiddenFromHelp
        get() = isHidden

    private val cliContext by requireObject<CliContext>()
    val appWiring
        get() = cliContext.appWiring

    val theme
        get() = cliContext.theme

    val renderer
        get() = cliContext.renderer

    val useFzf
        get() = cliContext.useFzf

    abstract suspend fun doRun()

    /**
     * Subcommands set this to true to suppress the trailing "update available" notice (e.g., when
     * the command's output is consumed by a script and a free-form line would break parsing).
     * Default false — the global `--no-update-check` flag plus the env/CI/TTY skip predicate cover
     * the common opt-outs already.
     */
    open val skipUpdateCheck: Boolean = false

    override suspend fun run() {
        val logger = Cli.logger
        try {
            doRun()
            cliContext.tipProvider?.getNextTip()?.let { tip ->
                renderer.info { muted("Tip: $tip") }
            }
            if (!skipUpdateCheck) {
                appWiring.updateCheck.maybeNotice()?.let { notice ->
                    renderer.info { muted(notice.formatMessage()) }
                }
            }
        } catch (e: GitJasprException) {
            logger.debug("An error occurred", e)
            renderer.error { e.message }
            throw ProgramResult(255)
        } catch (e: ProgramResult) {
            // Catch and rethrow to prevent the generic Exception handler from catching it
            throw e
        } catch (e: Exception) {
            logger.logUnhandledException(e)
            val errorMessage = e.message.orEmpty()
            if (errorMessage.isNotEmpty()) {
                renderer.error { errorMessage }
            }
            throw ProgramResult(255)
        } finally {
            logger.trace("Closing appWiring")
            withContext(Dispatchers.IO) { appWiring.close() }
        }
    }

    protected fun requireCountLocalExclusive(count: Int?, local: String) {
        require(count == null || local == DEFAULT_LOCAL_OBJECT) {
            "The --count and --local options are mutually exclusive."
        }
    }

    private fun Logger.logUnhandledException(exception: Exception) {
        error(exception.message, exception)
        error(
            "We're sorry, but you've likely encountered a bug. " +
                "Please consider enabling file logging and opening a bug report " +
                "with the log file attached."
        )
    }
}

// endregion

// region Commands

class Status : GitJasprSubcommand() {
    // language=Markdown
    override fun help(context: Context) =
        """
        Show the status of the current stack

        * **commit pushed** — The commit has been pushed to the remote.
        * **exists** — A pull request has been created for the given commit.
        * **checks pass** — GitHub checks pass.
        * **ready** — The PR is not a draft. Commits beginning with `DRAFT` or `WIP` are created in draft mode.
        * **approved** — The PR is approved.
        * **stack check** — This commit and all its parents in the stack are mergeable.

        """
            .trimIndent()

    private val targetRef by TargetRefOptions()

    override suspend fun doRun() {
        print(appWiring.gitJaspr.getStatusString(targetRef.refSpec, theme))
    }
}

class Compare : GitJasprSubcommand() {
    // language=Markdown
    override fun help(context: Context) =
        """
        Compare your local stack to the remote named stack side-by-side.

        Each commit pair appears on one row. The marker column shows whether they're
        content-identical (`==`), diverged (`~~`), or one-sided (blank).

        On diverged rows, the newer side is bold with a leading `*`; the older side is
        dimmed. Direction of "newer" is determined by commit date.

        Shared row index `[N]` lets the eye connect reordered commits that appear at
        different positions on the two sides.

        """
            .trimIndent()

    private val targetRef by TargetRefOptions()

    override suspend fun doRun() {
        print(appWiring.gitJaspr.getCompareString(targetRef.refSpec, theme))
    }
}

class Graph : GitJasprSubcommand() {
    // language=Markdown
    override fun help(context: Context) =
        """
        Show a `git log --graph` of your local stack, the remote named stack, and the
        target branch on the remote.

        Resolves the refs automatically: HEAD, the remote named-stack ref derived from
        the current branch (when exactly one matches), and `<remote>/<target>`. Refs
        that don't resolve are dropped silently.

        Pass extra args to `git log` after `--`, e.g.:

            jaspr graph -- --since='2 weeks ago'
            jaspr graph -- --all

        """
            .trimIndent()

    private val targetRef by TargetRefOptions()

    private val gitArgs by argument(name = "GIT_LOG_ARGS").multiple()

    override suspend fun doRun() {
        val refs = appWiring.gitJaspr.graphRefs(targetRef.refSpec)
        // Shell out directly so git renders its native --graph tree to the terminal;
        // GitClient's log methods parse into List<Commit> and would discard the graph.
        val cmd =
            listOf("git", "log", "--graph", "--oneline", "--decorate", "--abbrev-commit") +
                refs +
                gitArgs
        val exit =
            withContext(Dispatchers.IO) {
                ProcessBuilder(cmd)
                    .directory(appWiring.config.workingDirectory)
                    .inheritIO()
                    .start()
                    .waitFor()
            }
        if (exit != 0) throw ProgramResult(exit)
    }
}

class Pull : GitJasprSubcommand() {
    // language=Markdown
    override fun help(context: Context) =
        """
        Incorporate remote-only commits from the named stack into your local stack.

        Pull is non-interactive and transactional: it either completes its work
        without conflict, or refuses to start with a clear message pointing at
        `jaspr compare` and git. See ADR 0003 for the full decision tree.

        Cases pull handles automatically:

        * **fast-forward** — the remote has new commits or has rebased the shared
          portion; pull adopts the remote stack via `git reset --hard`.
        * **local-ahead / unpushed local work** — nothing to do; pull reports the
          state and suggests `jaspr push`.

        Cases pull refuses to handle (use git directly):

        * **divergence** — a shared commit has different content or message on the
          two sides.
        * **mixed unique work** — both sides have commits the other doesn't.
        * **unrelated bases** — the two stack bases share no history.

        """
            .trimIndent()

    private val targetRef by TargetRefOptions()

    private val theirs by
        option("--theirs").flag(default = false).help {
            "DANGEROUS. Resolve divergent commits (same commit-id, different content or " +
                "message) by replacing your local version with the remote's. Use only after " +
                "running `jaspr compare` to inspect what diverges. Before any destructive " +
                "change, pull writes a recovery ref to " +
                "`refs/jaspr-backup/pre-pull-<unix-timestamp>`; the ref is printed in " +
                "pull's output for one-command recovery via `git reset --hard <ref>`."
        }

    override suspend fun doRun() {
        print(appWiring.gitJaspr.pull(targetRef.refSpec, theirs, theme))
    }
}

class Push : GitJasprSubcommand(helpText = "Push commits and create/update PRs") {
    private val targetRef by TargetRefOptions()

    private val name by
        option()
            .help { "Name for the stack" }
            .convert { value -> StackNameGenerator.generateName(value.trim()) }
            .validate { value ->
                if (value.isEmpty()) {
                    fail("Stack name must contain at least one alphanumeric character")
                }
            }

    private val count by
        option("-c", "--count").int().help {
            "Limit commits from bottom of stack (negative excludes from top)"
        }

    private val force by
        option("-F", "--force").flag().help { "Push even if it would abandon open pull requests" }

    override suspend fun doRun() {
        requireCountLocalExclusive(count, targetRef.local)
        if (appWiring.gitClient.hasUncommittedChangesToTrackedFiles()) {
            throw GitJasprException(
                "Your working directory has uncommitted changes to tracked files. " +
                    "Please commit or stash them and re-run the command."
            )
        }
        val jaspr = appWiring.gitJaspr
        val plan = jaspr.getPushPlan(targetRef.refSpec, count)

        fun promptForNameIfNecessary(): String? {
            val suggestions = plan.stackNameSuggestions
            if (suggestions.candidates.isEmpty()) return null
            if (suggestions.ambiguousStackNames.isNotEmpty()) {
                renderer.warn {
                    "Commits exist in multiple stacks: " +
                        suggestions.ambiguousStackNames.joinToString(", ") { entity(it) } +
                        ". Select one to update it, or choose a new name."
                }
            }
            if (useFzf && suggestions.candidates.size > 1) {
                renderer.info {
                    "Pick a name for your stack from the list below, or type your own " +
                        "and press Enter " +
                        "(in the future you can use the ${command("--name")} option if you prefer)."
                }
                when (val result = selectNameViaFzf(suggestions.candidates)) {
                    is FzfResult.Selected -> return result.value
                    is FzfResult.Typed -> {
                        val normalized = StackNameGenerator.generateName(result.query)
                        if (normalized.isEmpty()) {
                            throw GitJasprException(
                                "Stack name must contain at least one alphanumeric character."
                            )
                        }
                        return normalized
                    }
                    is FzfResult.Cancelled -> throw ProgramResult(130)
                    is FzfResult.NotAvailable -> {} // fall through to prompt
                }
            }
            return promptForStackName(suggestions.candidates.first())
        }

        val effectiveName = name ?: promptForNameIfNecessary()
        jaspr.push(
            plan = plan,
            stackName = effectiveName,
            theme = theme,
            onAbandonedPrs =
                if (force) {
                    { true }
                } else {
                    { prs -> promptForAbandonedPrs(prs) }
                },
        )
    }

    private fun promptForAbandonedPrs(prs: List<PullRequest>): Boolean {
        val terminal = currentContext.terminal
        renderer.warn {
            "This push will abandon ${prs.size} open pull " +
                "${if (prs.size == 1) "request" else "requests"}:"
        }
        for (pr in prs) {
            renderer.info { "  ${url(pr.permalink.orEmpty())} : ${value(pr.title)}" }
        }
        echo()
        val response = terminal.prompt("Continue? [y/N]")?.trim()?.lowercase()
        return response == "y" || response == "yes"
    }

    private fun selectNameViaFzf(candidates: List<String>): FzfResult<String> =
        fzfSelect(
            items = candidates,
            displayLine = { it },
            key = { it },
            header = "Stack name (or type your own):",
            acceptTyped = true,
        )

    private fun promptForStackName(suggested: String): String {
        renderer.info {
            "Please provide a name for your stack or press enter to accept the generated one " +
                "(in the future you can use the ${command("--name")} option if you prefer)."
        }
        val terminal = currentContext.terminal
        var default = suggested
        while (true) {
            val input = terminal.prompt("Stack name", default = default.ifEmpty { null }) ?: default
            val normalized = StackNameGenerator.generateName(input)
            if (normalized.isEmpty()) {
                renderer.error { "Stack name must contain at least one alphanumeric character." }
                default = ""
                continue
            }
            if (normalized == input) return input
            renderer.info { "Normalized to: ${entity(normalized)}" }
            default = normalized
        }
    }
}

class Merge : GitJasprSubcommand(helpText = "Merge all mergeable commits") {
    private val targetRef by TargetRefOptions()

    private val count by
        option("-c", "--count").int().help {
            "Limit commits from bottom of stack (negative excludes from top)"
        }

    override suspend fun doRun() {
        requireCountLocalExclusive(count, targetRef.local)
        appWiring.gitJaspr.merge(targetRef.refSpec, count = count)
    }
}

class AutoMerge : GitJasprSubcommand(helpText = "Wait for checks then merge") {
    private val targetRef by TargetRefOptions()

    private val count by
        option("-c", "--count").int().help {
            "Limit commits from bottom of stack (negative excludes from top)"
        }

    private val interval by
        option("--interval", "-i").int().default(10).help { "Polling interval in seconds" }

    override suspend fun doRun() {
        requireCountLocalExclusive(count, targetRef.local)
        appWiring.gitJaspr.autoMerge(targetRef.refSpec, interval, count = count, theme = theme)
    }
}

class Clean : GitJasprSubcommand(helpText = "Clean up orphaned branches") {
    private val cleanOpts by CleanBehaviorOptions()

    override suspend fun doRun() {
        val jaspr = appWiring.gitJaspr
        val terminal = currentContext.terminal
        var cleanAbandonedPrs = cleanOpts.cleanAbandonedPrs
        var cleanJustMyPrs = !cleanOpts.cleanAllCommits

        fun Theme.onOff(v: Boolean) = if (v) success("on") else error("off")

        while (true) {
            renderer.info { "Finding branches to clean (this may take a minute)..." }
            val plan =
                jaspr.getCleanPlan(
                    cleanAbandonedPrs = cleanAbandonedPrs,
                    cleanAllCommits = !cleanJustMyPrs,
                )
            withContext(Dispatchers.IO) { displayPlan(plan, jaspr) }

            if (plan.allBranches().isEmpty()) {
                renderer.info { success("Nothing to clean.") }
                return
            }

            echo()
            renderer.info { "Options:" }
            renderer.info { "  [${keyHint("a")}] Clean abandoned PRs: ${onOff(cleanAbandonedPrs)}" }
            renderer.info { "  [${keyHint("m")}] Clean just my PRs: ${onOff(cleanJustMyPrs)}" }
            echo()

            val prompt =
                "Perform [${theme.keyHint("c")}]lean, toggle [${theme.keyHint("a")}]bandoned, " +
                    "toggle [${theme.keyHint("m")}]ine, or [${theme.keyHint("q")}]uit"

            // Inner loop: keeps re-prompting on invalid input without re-fetching the plan.
            // Breaks out (to re-fetch) only when the user toggles an option that affects the plan.
            while (true) {
                when (terminal.prompt(prompt)?.trim()?.lowercase()) {
                    "c" -> {
                        val finalPlan =
                            jaspr.closeAbandonedPrsAndRecalculate(
                                plan,
                                cleanAbandonedPrs,
                                !cleanJustMyPrs,
                            )
                        jaspr.executeCleanPlan(finalPlan)
                        val count = finalPlan.allBranches().size
                        renderer.info {
                            success("Deleted $count ${if (count == 1) "branch" else "branches"}.")
                        }
                        return
                    }

                    "a" -> {
                        cleanAbandonedPrs = !cleanAbandonedPrs
                        break
                    }

                    "m" -> {
                        cleanJustMyPrs = !cleanJustMyPrs
                        break
                    }

                    "q",
                    null -> {
                        renderer.warn { "Aborted." }
                        return
                    }

                    else -> renderer.error { "Invalid selection." }
                }
            }
        }
    }

    private fun displayPlan(plan: GitJaspr.CleanPlan, jaspr: GitJaspr) {
        val shortMessages =
            jaspr.getShortMessagesForBranches(
                (plan.orphanedBranches + plan.abandonedBranches).toList()
            )

        val lines = buildList {
            if (plan.orphanedBranches.isNotEmpty()) {
                add("")
                add(theme.heading("Orphaned branches (PRs are closed or do not exist):"))
                for (branch in plan.orphanedBranches) {
                    val message =
                        shortMessages[branch]?.let { " ${theme.commitSubject(it)}" }.orEmpty()
                    add("  ${theme.entity(branch)}$message")
                }
            }

            if (plan.emptyNamedStackBranches.isNotEmpty()) {
                add("")
                add(theme.heading("Empty named stack branches (fully merged):"))
                for (branch in plan.emptyNamedStackBranches) {
                    add("  ${theme.entity(branch)}")
                }
            }

            if (plan.abandonedNamedStackBranches.isNotEmpty()) {
                add("")
                add(
                    theme.heading(
                        "Abandoned named stack branches (underlying jaspr branches no longer exist):"
                    )
                )
                for (branch in plan.abandonedNamedStackBranches) {
                    add("  ${theme.entity(branch)}")
                }
            }

            if (plan.abandonedBranches.isNotEmpty()) {
                add("")
                add(
                    theme.heading("Abandoned branches (open PRs not reachable by any named stack):")
                )
                for (branch in plan.abandonedBranches) {
                    val message =
                        shortMessages[branch]?.let { " ${theme.commitSubject(it)}" }.orEmpty()
                    add("  ${theme.entity(branch)}$message")
                }
            }
        }

        printPaged(lines)
    }
}

class Checkout : GitJasprSubcommand(helpText = "Check out an existing named stack") {
    private val targetOpts by TargetOptions()

    private val name by option("-n", "--name").help { "Stack name (skips interactive selection)" }

    override suspend fun doRun() {
        val gitJaspr = appWiring.gitJaspr
        val config = appWiring.config
        val target = targetOpts.target
        renderer.info { "Finding named stacks (this may take a minute)..." }
        val allEntries = gitJaspr.getAllNamedStacksWithStatus()
        // Empty stacks (already merged into target) aren't useful to check out interactively, but
        // the user can still reach them via -n NAME for inspection.
        val entries = allEntries.filter { it.ref.targetRef == target }
        val selectableEntries = entries.filter { !it.isEmpty }
        if (selectableEntries.isEmpty()) {
            renderer.error {
                buildString {
                    append(
                        "No named stacks found targeting '${entity(target)}' " +
                            "(searching ${entity("${config.remoteNamedStackBranchPrefix}/$target/*")})."
                    )
                    val otherStacks =
                        allEntries
                            .filter { it.ref.targetRef != target && !it.isEmpty }
                            .map { it.ref }
                    if (otherStacks.isNotEmpty()) {
                        appendLine()
                        appendLine("Named stacks exist for other targets:")
                        for (stack in otherStacks.take(5)) {
                            appendLine("  [${entity(stack.targetRef)}] ${entity(stack.stackName)}")
                        }
                        if (otherStacks.size > 5) {
                            appendLine("  ... and ${otherStacks.size - 5} more")
                        }
                        append("Use ${command("-t/--target")} to specify a different target.")
                    }
                }
            }
            throw ProgramResult(255)
        }

        val selected =
            if (name != null) {
                val found = entries.find { it.ref.stackName == name }?.ref
                if (found == null) {
                    renderer.error {
                        "No named stack '${entity(name)}' found targeting '${entity(target)}'. " +
                            "Available stacks: " +
                            entries.joinToString(", ") { entity(it.ref.stackName) }
                    }
                    throw ProgramResult(255)
                }
                found
            } else {
                val remoteName = config.remoteName
                val unsortedStacks = selectableEntries.map { it.ref }
                val abandonedNames =
                    selectableEntries.filter { it.isAbandoned }.map { it.ref.stackName }.toSet()
                val refs = unsortedStacks.map { "${remoteName}/${it.name()}" }
                val commits = appWiring.gitClient.getCommits(refs)
                val stacks = sortStacksByTipDate(unsortedStacks, commits, remoteName)
                when (
                    val result = selectViaFzf(stacks, commits, remoteName, target, abandonedNames)
                ) {
                    is FzfResult.Selected -> result.value
                    is FzfResult.Cancelled,
                    is FzfResult.Typed -> throw ProgramResult(130)
                    is FzfResult.NotAvailable ->
                        selectViaPrompt(stacks, commits, remoteName, target, abandonedNames)
                }
            }

        gitJaspr.checkoutNamedStack(selected)
    }

    private fun selectViaFzf(
        stacks: List<RemoteNamedStackRef>,
        commits: Map<String, Commit?>,
        remoteName: String,
        target: String,
        abandonedNames: Set<String>,
    ): FzfResult<RemoteNamedStackRef> {
        if (!useFzf) return FzfResult.NotAvailable
        val prefix = stacks.first().prefix
        return fzfSelect(
            items = stacks,
            key = RemoteNamedStackRef::stackName,
            displayLine = { stack ->
                // Raw ANSI codes with full resets (\e[0m) instead of Mordant's specific resets
                // (\e[39m, \e[22m) to work around an fzf rendering bug where the first line in
                // --reverse mode renders incorrectly with specific reset codes.
                val ref = "${remoteName}/${stack.name()}"
                val commit = commits[ref]
                val subject = commit?.shortMessage?.let { "  \u001b[97m$it\u001b[0m" }.orEmpty()
                val marker =
                    if (stack.stackName in abandonedNames) " \u001b[2m[abandoned]\u001b[0m" else ""
                val author = commit?.author?.name?.let { "  \u001b[2m<$it>\u001b[0m" }.orEmpty()
                "\u001b[36m${stack.stackName}\u001b[0m$marker$subject$author"
            },
            header = "Named stacks targeting $target:",
            previewCommand =
                "git log --color=always --graph -20" +
                    " --pretty=format:'%C(red)%h%Creset %s %C(green)(%ar) %C(bold blue)<%an>%Creset'" +
                    " $remoteName/$prefix/$target/{1} ^$remoteName/$target" +
                    " ; git log --color=always --graph -8" +
                    " --pretty=format:'%C(dim)%h %s (%ar) <%an>%Creset'" +
                    $$" $(git merge-base $$remoteName/$$prefix/$$target/{1} $$remoteName/$$target)",
        )
    }

    private suspend fun selectViaPrompt(
        stacks: List<RemoteNamedStackRef>,
        commits: Map<String, Commit?>,
        remoteName: String,
        target: String,
        abandonedNames: Set<String>,
    ): RemoteNamedStackRef {
        val lines = buildList {
            add(theme.heading("Named stacks targeting ${theme.entity(target)}:"))
            for ((index, stack) in stacks.withIndex()) {
                val ref = "${remoteName}/${stack.name()}"
                val commit = commits[ref]
                val message = commit?.shortMessage?.let { " ${theme.commitSubject(it)}" }.orEmpty()
                val author = commit?.author?.name?.let { " ${theme.muted("<$it>")}" }.orEmpty()
                val marker =
                    if (stack.stackName in abandonedNames) " ${theme.muted("[abandoned]")}" else ""
                add(
                    "  ${theme.keyHint("${index + 1}.")} " +
                        "[${theme.entity(stack.stackName)}]$marker$message$author"
                )
            }
        }
        withContext(Dispatchers.IO) { printPaged(lines) }
        val terminal = currentContext.terminal
        while (true) {
            val input = terminal.prompt("Select a stack (1-${stacks.size})")
            val selection = input?.toIntOrNull()
            if (selection != null && selection in 1..stacks.size) {
                return stacks[selection - 1]
            }
            renderer.error {
                "Invalid selection. Please enter a number between 1 and ${stacks.size}."
            }
        }
    }
}

class Rebase : GitJasprSubcommand() {
    // language=Markdown
    override fun help(context: Context) =
        """
        Rebase the current stack onto the latest target branch

        Fetches the latest changes from the remote and rebases your stack onto the updated target branch.
        """
            .trimIndent()

    private val targetOpts by TargetOptions()

    override suspend fun doRun() {
        val config = appWiring.config
        val remoteName = config.remoteName
        val target = targetOpts.target
        val workingDirectory = appWiring.gitClient.workingDirectory

        renderer.info {
            "Rebasing stack onto ${entity("$remoteName/$target")}. " +
                "If you encounter merge conflicts, you can abort via ${command("git rebase --abort")} " +
                "and perform the rebase manually if you wish."
        }

        val rebaseResult =
            withContext(Dispatchers.IO) {
                val fetchResult =
                    ProcessBuilder("git", "fetch", remoteName)
                        .directory(workingDirectory)
                        .inheritIO()
                        .start()
                        .waitFor()

                if (fetchResult != 0) {
                    renderer.error { "Fetch failed with exit code $fetchResult." }
                    throw ProgramResult(fetchResult)
                }

                val rebaseArgs = buildList {
                    add("git")
                    add("rebase")
                    add("--autosquash")
                    if (!gitSupportsNonInteractiveAutosquash(workingDirectory)) {
                        add("--interactive")
                    }
                    add("$remoteName/$target")
                }

                ProcessBuilder(rebaseArgs)
                    .directory(workingDirectory)
                    .inheritIO()
                    // GIT_SEQUENCE_EDITOR=true is needed when --interactive is used to prevent
                    // the editor from opening. It's harmless when --interactive is absent.
                    .apply { environment()["GIT_SEQUENCE_EDITOR"] = "true" }
                    .start()
                    .waitFor()
            }

        if (rebaseResult != 0) {
            renderer.warn {
                "Rebase stopped (exit code $rebaseResult). " +
                    "Resolve any conflicts, stage the files, then run ${command("jaspr continue")}."
            }
            throw ProgramResult(rebaseResult)
        }
    }
}

class Sync :
    GitJasprSubcommand(helpText = "Rebase all local jaspr stacks onto the latest target branch") {
    private val targetOpts by TargetOptions()

    override suspend fun doRun() {
        val results = appWiring.gitJaspr.sync(targetOpts.target)
        val succeeded = results.count { it.success }
        val failed = results.count { !it.success }
        if (failed > 0) {
            renderer.warn {
                "Sync complete: $succeeded succeeded, $failed failed. " +
                    "Failed branches: ${results.filter { !it.success }.joinToString(", ") { entity(it.branch) }}"
            }
        } else if (succeeded > 0) {
            renderer.info {
                success(
                    "Sync complete: $succeeded ${if (succeeded == 1) "branch" else "branches"} rebased."
                )
            }
        }
    }
}

private const val GIT_AUTOSQUASH_MIN_VERSION = "2.44.0"

/** Returns true if the installed git version supports `--autosquash` without `--interactive`. */
private fun gitSupportsNonInteractiveAutosquash(workingDirectory: File): Boolean {
    val versionOutput =
        ProcessBuilder("git", "--version")
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()
    return isGitVersionAtLeast(versionOutput, GIT_AUTOSQUASH_MIN_VERSION)
}

/** Packs a major.minor.patch version into a single comparable integer. */
private fun versionNumber(parts: List<Int>) = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]

private fun parseVersionParts(version: String) =
    version.split(".").take(3).mapNotNull { it.toIntOrNull() }

/**
 * Parses a `git --version` output string and returns true if the version is at least [minVersion].
 *
 * Expected format: `"git version 2.51.2"` (may have additional suffix like `".windows.1"`).
 * [minVersion] is a dotted version string like `"2.44.0"`.
 */
fun isGitVersionAtLeast(gitVersionOutput: String, minVersion: String): Boolean {
    val actual = parseVersionParts(gitVersionOutput.removePrefix("git version "))
    if (actual.size < 3) return false
    return versionNumber(actual) >= versionNumber(parseVersionParts(minVersion))
}

class Edit(name: String? = null) : GitJasprSubcommand(name = name) {
    // language=Markdown
    override fun help(context: Context) =
        """
        Edit your stack via interactive rebase

        Opens an interactive rebase for the commits in your stack, allowing you to reorder, edit, squash, or drop commits.
        """
            .trimIndent()

    private val targetOpts by TargetOptions()

    override suspend fun doRun() {
        val jaspr = appWiring.gitJaspr
        if (jaspr.readNavState() != null && appWiring.gitClient.isHeadDetached()) {
            renderer.error {
                "Cannot edit while a navigation session is active. " +
                    "Run ${command("jaspr top")} to return to the branch first, " +
                    "or ${command("jaspr nav cancel")} to discard the session."
            }
            throw ProgramResult(1)
        }

        val config = appWiring.config
        val remoteName = config.remoteName
        val target = targetOpts.target
        val workingDirectory = appWiring.gitClient.workingDirectory

        withContext(Dispatchers.IO) {
            val mergeBaseProcess =
                ProcessBuilder("git", "merge-base", "HEAD", "$remoteName/$target")
                    .directory(workingDirectory)
                    .redirectErrorStream(true)
                    .start()

            val mergeBase = mergeBaseProcess.inputStream.bufferedReader().readText().trim()
            val mergeBaseExit = mergeBaseProcess.waitFor()

            if (mergeBaseExit != 0 || mergeBase.isEmpty()) {
                renderer.error {
                    "Could not determine merge base with ${entity("$remoteName/$target")}."
                }
                throw ProgramResult(255)
            }

            // Write a temporary script that prepends a helpful header to the rebase TODO list,
            // then opens the user's editor. GIT_SEQUENCE_EDITOR replaces the editor for the TODO
            // list, so the script must invoke the real editor after modifying the file.
            val scriptFile = File.createTempFile("jaspr-edit-", ".sh")
            try {
                scriptFile.writeText(
                    $$"""
                    #!/bin/sh
                    header_file=$(mktemp)
                    cat > "$header_file" << 'HEADER'
                    # Edit your stack by modifying the list below.
                    #
                    # Reorder:  Move lines up or down to change commit order.
                    # Drop:     Delete a line to remove that commit.
                    # Squash:   Change 'pick' to 's' to merge into the previous commit.
                    # Edit:     Change 'pick' to 'e', then:
                    #             1. Make your changes
                    #             2. Stage them: git add <files>
                    #             3. Amend: git commit --amend
                    #             4. Continue: jaspr continue
                    #
                    HEADER
                    cat "$1" >> "$header_file"
                    mv "$header_file" "$1"
                    eval "$(git var GIT_EDITOR)" "$1"
                    """
                        .trimIndent()
                )
                scriptFile.setExecutable(true)

                val rebaseResult =
                    ProcessBuilder("git", "rebase", "-i", mergeBase)
                        .directory(workingDirectory)
                        .inheritIO()
                        .apply { environment()["GIT_SEQUENCE_EDITOR"] = scriptFile.absolutePath }
                        .start()
                        .waitFor()

                if (rebaseResult != 0) {
                    throw ProgramResult(rebaseResult)
                }
            } finally {
                scriptFile.delete()
            }
        }
    }
}

class InstallHook : GitJasprSubcommand(helpText = "Install the jaspr commit-msg hook") {
    override suspend fun doRun() {
        appWiring.gitJaspr.installCommitIdHook()
    }
}

// region Navigation commands

private fun requireNoActiveSplit(jaspr: GitJaspr) {
    require(!jaspr.isSplitInProgress()) {
        "A split is in progress. Run jaspr unsplit to restore the original commit, " +
            "or commit your changes and run jaspr top to finish splitting."
    }
}

class Down : GitJasprSubcommand(helpText = "Move down in the stack (toward the target branch)") {
    private val targetOpts by TargetOptions()
    private val n by argument("n").int().optional()

    override suspend fun doRun() {
        val jaspr = appWiring.gitJaspr
        requireNoActiveSplit(jaspr)
        if (jaspr.isNavStateStale()) {
            renderer.warn {
                "Stale navigation state detected (you are on a branch). " +
                    "Run ${command("jaspr nav cancel")} to clear it, or it will be replaced."
            }
            jaspr.clearNavState()
        }
        val newState = jaspr.navigateDown(targetOpts.target, n ?: 1)
        print(jaspr.getNavPositionString(newState, theme))
    }
}

class Bottom : GitJasprSubcommand(helpText = "Move to the bottom of the stack") {
    private val targetOpts by TargetOptions()

    override suspend fun doRun() {
        val jaspr = appWiring.gitJaspr
        requireNoActiveSplit(jaspr)
        if (jaspr.isNavStateStale()) {
            renderer.warn {
                "Stale navigation state detected (you are on a branch). " +
                    "Run ${command("jaspr nav cancel")} to clear it, or it will be replaced."
            }
            jaspr.clearNavState()
        }
        val newState = jaspr.navigateToBottom(targetOpts.target)
        print(jaspr.getNavPositionString(newState, theme))
    }
}

class Up : GitJasprSubcommand(helpText = "Move up in the stack (replay commits toward the tip)") {
    private val targetOpts by TargetOptions()
    private val n by argument("n").int().optional()

    override suspend fun doRun() {
        val jaspr = appWiring.gitJaspr
        requireNoActiveSplit(jaspr)
        when (val result = jaspr.navigateUp(n ?: 1, targetOpts.target)) {
            NavMoveResult.NoSession -> renderer.info { "Already at the top of the stack." }
            is NavMoveResult.MovedWithin -> print(jaspr.getNavPositionString(result.state, theme))
            is NavMoveResult.ReachedTop ->
                renderer.info { describeReachedTop(result.replayedCount, result.restoredName) }
        }
    }
}

class Top :
    GitJasprSubcommand(helpText = "Move to the top of the stack (replay all remaining commits)") {
    private val targetOpts by TargetOptions()

    override suspend fun doRun() {
        val jaspr = appWiring.gitJaspr
        jaspr.clearSplitState()
        when (val result = jaspr.navigateToTop(targetOpts.target)) {
            NavMoveResult.NoSession -> renderer.info { "Already at the top of the stack." }
            is NavMoveResult.MovedWithin -> print(jaspr.getNavPositionString(result.state, theme))
            is NavMoveResult.ReachedTop ->
                renderer.info { describeReachedTop(result.replayedCount, result.restoredName) }
        }
    }
}

class Goto :
    GitJasprSubcommand(
        helpText = "Move to position N in the stack (1 = bottom, -1 = top, -2 = second from top)"
    ) {
    private val targetOpts by TargetOptions()
    private val position by argument("n").int()

    override suspend fun doRun() {
        val jaspr = appWiring.gitJaspr
        requireNoActiveSplit(jaspr)
        if (jaspr.isNavStateStale()) {
            renderer.warn {
                "Stale navigation state detected (you are on a branch). " +
                    "Run ${command("jaspr nav cancel")} to clear it, or it will be replaced."
            }
            jaspr.clearNavState()
        }
        when (val result = jaspr.navigateTo(targetOpts.target, position)) {
            NavMoveResult.NoSession -> renderer.info { "Already at the top of the stack." }
            is NavMoveResult.MovedWithin -> print(jaspr.getNavPositionString(result.state, theme))
            is NavMoveResult.ReachedTop ->
                renderer.info { describeReachedTop(result.replayedCount, result.restoredName) }
        }
    }
}

/**
 * Order named-stack picker entries by tip commit date (newest first) so recently-touched stacks
 * rise to the top of long lists. Stacks whose commit data is missing fall to the bottom; stack name
 * breaks ties for stable display.
 */
internal fun sortStacksByTipDate(
    stacks: List<RemoteNamedStackRef>,
    commits: Map<String, Commit?>,
    remoteName: String,
): List<RemoteNamedStackRef> =
    stacks.sortedWith(
        compareBy<RemoteNamedStackRef, ZonedDateTime?>(nullsLast(reverseOrder())) { stack ->
                commits["${remoteName}/${stack.name()}"]?.commitDate
            }
            .thenBy(RemoteNamedStackRef::stackName)
    )

private fun describeReachedTop(count: Int, restoredName: String): String {
    val noun = if (count == 1) "commit" else "commits"
    return "Replayed $count $noun, back on `$restoredName`."
}

class Nav : SuspendingCliktCommand(name = "nav") {
    override fun help(context: Context) = "Navigation session management"

    // `jaspr nav abort` is a hidden alias for `jaspr nav cancel`. Matches the verb users will
    // reach for from git muscle memory (`git rebase --abort`, etc.) without bloating help.
    override fun aliases(): Map<String, List<String>> = mapOf("abort" to listOf("cancel"))

    override suspend fun run() = Unit
}

class NavCancel :
    GitJasprSubcommand(
        name = "cancel",
        helpText =
            "Cancel navigation session and restore the original branch " +
                "(discards uncommitted changes; stash first to preserve them)",
    ) {
    override suspend fun doRun() {
        val jaspr = appWiring.gitJaspr
        val state = jaspr.readNavState()
        if (state == null) {
            renderer.info { "No navigation session to cancel." }
            return
        }
        if (!appWiring.gitClient.isHeadDetached()) {
            // Stale state — just clean it up
            jaspr.clearNavState()
            renderer.info { "Stale navigation state cleared." }
            return
        }
        val splitWasActive = jaspr.isSplitInProgress()
        val orphanedShas = jaspr.cancelNavSession()
        renderer.info {
            "Navigation session cancelled. Restored ${entity(state.headBeforeDetach)}."
        }
        if (splitWasActive) {
            renderer.warn {
                "In-progress split discarded; working tree and untracked files were cleaned."
            }
        }
        if (orphanedShas.isNotEmpty()) {
            val shortMessages = appWiring.gitClient.getShortMessages(orphanedShas)
            renderer.warn { "The following commits are now orphaned (not on any branch):" }
            for (sha in orphanedShas) {
                val message = shortMessages[sha]?.let { " ${theme.commitSubject(it)}" }.orEmpty()
                renderer.warn { "  ${entity(sha.take(7))}$message" }
            }
            renderer.warn {
                "To recover them, use ${command("git checkout <sha>")} before they are garbage collected."
            }
        }
    }
}

class NavFinish :
    GitJasprSubcommand(
        name = "finish",
        helpText = "End navigation session, keeping only commits below the cursor",
    ) {
    override suspend fun doRun() {
        val jaspr = appWiring.gitJaspr
        requireNoActiveSplit(jaspr)
        val discarded = jaspr.finishNavSession()
        if (discarded.isNotEmpty()) {
            val count = discarded.size
            val commits = if (count == 1) "commit" else "commits"
            renderer.warn { "Discarded $count $commits from the replay queue:" }
            for (entry in discarded) {
                renderer.warn { "  ${entity(entry.commitId)}" }
            }
        }
    }
}

class Drop : GitJasprSubcommand(helpText = "Drop the top N commits from the stack (default 1)") {
    private val targetOpts by TargetOptions()
    private val n by argument("n").int().optional()

    override suspend fun doRun() {
        requireNoActiveSplit(appWiring.gitJaspr)
        appWiring.gitJaspr.drop(n ?: 1, targetOpts.target)
    }
}

class Split : GitJasprSubcommand(helpText = "Split the HEAD commit into working tree changes") {
    override suspend fun doRun() {
        val jaspr = appWiring.gitJaspr
        val wasInNavSession = jaspr.isNavSessionActive()
        val subject = jaspr.split()
        renderer.info {
            "Commit ${entity(subject)} has been reset. Its changes are in your working tree."
        }
        renderer.info {
            buildString {
                append("You may create new commits from these changes")
                if (!wasInNavSession) {
                    append(".")
                } else {
                    append(", then ${command("jaspr top")} to replay the rest of the stack.")
                }
            }
        }
        renderer.info { "To undo: ${command("jaspr unsplit")}" }
    }
}

class Unsplit :
    GitJasprSubcommand(
        helpText = "Restore the original commit (fold working tree, or replay on top of new work)"
    ) {
    override suspend fun doRun() {
        when (val outcome = appWiring.gitJaspr.unsplit()) {
            is UnsplitOutcome.Restored ->
                renderer.info {
                    "Commit ${entity(outcome.restoredCommit.shortMessage)} restored."
                }
            is UnsplitOutcome.RestoredWithAutoResolvedConflicts -> {
                renderer.info {
                    "Commit ${entity(outcome.restoredCommit.shortMessage)} restored."
                }
                val count = outcome.conflictingPaths.size
                val files = if (count == 1) "file" else "files"
                renderer.info {
                    buildString {
                        appendLine()
                        appendLine(
                            emphasis(
                                "$count $files had conflicts that were auto-resolved " +
                                    "(the changes in the unsplit commit were accepted):"
                            )
                        )
                        outcome.conflictingPaths.forEach { path -> appendLine(entity(path)) }
                        appendLine()
                        appendLine("It is recommended to review the result before pushing:")
                        appendLine(command("git diff ${outcome.backupRef}"))
                        appendLine()
                        appendLine("To undo:")
                        appendLine(command("git reset --hard ${outcome.backupRef}"))
                        if (outcome.stashRef != null) {
                            appendLine()
                            appendLine(
                                "Your pre-unsplit working tree was saved at " +
                                    "${entity(outcome.stashRef)}. Apply it with:"
                            )
                            appendLine(command("git stash apply ${outcome.stashRef}"))
                        }
                    }
                }
            }
            is UnsplitOutcome.LeftInProgress -> {
                val commit = outcome.originalCommit
                renderer.info {
                    buildString {
                        appendLine()
                        appendLine(
                            "Replaying ${commitSubject(commit.shortMessage)} " +
                                "(${hash(commit.hash.take(7))}) hit a conflict the merge " +
                                "strategy could not auto-resolve."
                        )
                        appendLine()
                        appendLine(
                            "A cherry-pick is in progress. Resolve manually and continue, or " +
                                "abort:"
                        )
                        appendLine(command("git cherry-pick --continue"))
                        appendLine(command("git cherry-pick --abort"))
                        appendLine()
                        append("Or run ")
                        append(command("jaspr nav cancel"))
                        append(" to abort the navigation session entirely.")
                        appendLine()
                        appendLine()
                        appendLine(
                            "Your pre-unsplit HEAD is saved at ${entity(outcome.backupRef)}."
                        )
                        if (outcome.stashRef != null) {
                            appendLine(
                                "Your pre-unsplit working tree was saved at " +
                                    "${entity(outcome.stashRef)}. Apply it with:"
                            )
                            appendLine(command("git stash apply ${outcome.stashRef}"))
                        }
                    }
                }
                throw ProgramResult(255)
            }
        }
    }
}

class Fold :
    GitJasprSubcommand(helpText = "Fold (squash) the current commit into an adjacent commit") {
    private val direction by argument("direction").optional()

    override suspend fun doRun() {
        val subject = appWiring.gitJaspr.fold(direction ?: "down")
        renderer.info { "Folded into ${entity(subject)}." }
    }
}

// endregion

class Fixup : GitJasprSubcommand(helpText = "Create a fixup commit targeting a stack commit") {
    private val targetOpts by TargetOptions()

    override suspend fun doRun() {
        val gitClient = appWiring.gitClient
        val remoteName = appWiring.config.remoteName
        val workingDirectory = gitClient.workingDirectory

        // Check for staged changes
        val hasStagedChanges =
            withContext(Dispatchers.IO) {
                ProcessBuilder("git", "diff", "--cached", "--quiet")
                    .directory(workingDirectory)
                    .start()
                    .waitFor() != 0
            }
        if (!hasStagedChanges) {
            renderer.error {
                "No staged changes. Stage the changes you want to fix up, then run ${command("jaspr fixup")} again."
            }
            throw ProgramResult(1)
        }

        gitClient.fetch(remoteName)
        val stack = gitClient.getCommitStack(remoteName, GitClient.HEAD, targetOpts.target)
        if (stack.isEmpty()) {
            renderer.error { "Stack is empty." }
            throw ProgramResult(1)
        }

        val fzfResult =
            if (!useFzf) {
                FzfResult.NotAvailable
            } else {
                fzfSelect(
                    items = stack,
                    key = Commit::hash,
                    displayLine = { commit ->
                        "\u001b[33m${commit.hash.take(7)}\u001b[0m \u001b[97m${commit.shortMessage}\u001b[0m"
                    },
                    header = "Select commit to fix up:",
                )
            }
        val selected =
            when (fzfResult) {
                is FzfResult.Selected -> fzfResult.value
                is FzfResult.Cancelled,
                is FzfResult.Typed -> throw ProgramResult(130)
                is FzfResult.NotAvailable -> selectFixupViaPrompt(stack)
            }

        val result =
            withContext(Dispatchers.IO) {
                ProcessBuilder("git", "commit", "--fixup=${selected.hash}")
                    .directory(workingDirectory)
                    .inheritIO()
                    .start()
                    .waitFor()
            }
        if (result != 0) throw ProgramResult(result)

        renderer.info {
            "Fixup commit created targeting ${entity(selected.hash.take(7))} " +
                "(${entity(selected.shortMessage)}). " +
                "Run ${command("jaspr rebase")} to fold it in."
        }
    }

    private suspend fun selectFixupViaPrompt(stack: List<Commit>): Commit {
        val lines = buildList {
            add(theme.heading("Commits in stack:"))
            for ((index, commit) in stack.withIndex()) {
                add(
                    "  ${theme.keyHint("${index + 1}.")} " +
                        "${theme.muted(commit.hash.take(7))} ${theme.commitSubject(commit.shortMessage)}"
                )
            }
        }
        withContext(Dispatchers.IO) { printPaged(lines) }
        val terminal = currentContext.terminal
        while (true) {
            val input = terminal.prompt("Select commit to fix up (1-${stack.size})")
            val selection = input?.toIntOrNull()
            if (selection != null && selection in 1..stack.size) {
                return stack[selection - 1]
            }
            renderer.error {
                "Invalid selection. Please enter a number between 1 and ${stack.size}."
            }
        }
    }
}

class Continue :
    GitJasprSubcommand(helpText = "Continue an in-progress operation (rebase or cherry-pick)") {
    override suspend fun doRun() {
        val workingDirectory = appWiring.gitClient.workingDirectory
        val gitDir = workingDirectory.resolve(".git")

        // Prefer rebase over cherry-pick since rebase is the higher-level operation
        val command =
            when {
                gitDir.resolve("rebase-merge").exists() ||
                    gitDir.resolve("rebase-apply").exists() -> listOf("git", "rebase", "--continue")
                gitDir.resolve("CHERRY_PICK_HEAD").exists() ->
                    listOf("git", "cherry-pick", "--continue")
                else -> {
                    renderer.info { "Nothing to continue." }
                    return
                }
            }

        val result =
            withContext(Dispatchers.IO) {
                ProcessBuilder(command).directory(workingDirectory).inheritIO().start().waitFor()
            }
        if (result != 0) throw ProgramResult(result)
    }
}

class Stack : SuspendingCliktCommand(name = "stack") {
    override fun help(context: Context) = "Manage named stacks"

    override suspend fun run() = Unit
}

class StackList : GitJasprSubcommand(name = "list", helpText = "List all named stacks") {

    private val mine by
        option("--mine").flag().help { "Show only stacks authored by the current user" }

    override suspend fun doRun() {
        val gitJaspr = appWiring.gitJaspr
        val config = appWiring.config
        val stacks = gitJaspr.getAllNamedStacks(mineOnly = mine)

        if (stacks.isEmpty()) {
            renderer.info { if (mine) "No matching stacks found." else "No named stacks found." }
            return
        }

        val remoteName = config.remoteName
        val refs = stacks.map { ref -> "${remoteName}/${ref.name()}" }
        val commits = appWiring.gitClient.getCommits(refs)

        val stacksByTarget = stacks.groupBy(RemoteNamedStackRef::targetRef)
        val lines = buildList {
            for ((targetRef, targetStacks) in stacksByTarget) {
                add(theme.heading("Stacks targeting ${theme.entity(targetRef)}:"))
                for (stack in targetStacks) {
                    val ref = "${remoteName}/${stack.name()}"
                    val commit = commits[ref]
                    val message =
                        commit?.shortMessage?.let { " ${theme.commitSubject(it)}" }.orEmpty()
                    val author =
                        if (!mine) {
                            commit?.author?.name?.let { " ${theme.muted("<$it>")}" }.orEmpty()
                        } else {
                            ""
                        }
                    add("  [${theme.entity(stack.stackName)}]$message$author")
                }
                add("")
            }
        }
        withContext(Dispatchers.IO) { printPaged(lines) }
    }
}

class StackRename : GitJasprSubcommand(name = "rename", helpText = "Rename a named stack") {
    private val targetOpts by TargetOptions()

    private val oldName by argument(help = "The current name of the stack")
    private val newName by
        argument(help = "The new name for the stack").convert { value ->
            StackNameGenerator.generateName(value.trim())
        }

    override suspend fun doRun() {
        if (newName.isEmpty()) {
            renderer.error { "New stack name must contain at least one alphanumeric character." }
            throw ProgramResult(255)
        }
        appWiring.gitJaspr.renameStack(oldName, newName, targetOpts.target)
        renderer.info { success("Renamed stack '${entity(oldName)}' to '${entity(newName)}'.") }
    }
}

class StackDelete :
    GitJasprSubcommand(name = "delete", helpText = "Delete a named stack from the remote") {
    private val targetOpts by TargetOptions()

    private val name by argument(help = "The name of the stack to delete")

    override suspend fun doRun() {
        val gitJaspr = appWiring.gitJaspr
        val config = appWiring.config
        val target = targetOpts.target
        val remoteName = config.remoteName
        val prefix = config.remoteNamedStackBranchPrefix
        val stackRef = RemoteNamedStackRef(name, target, prefix).name()
        val ref = "$remoteName/$stackRef"

        // Show the stack's commits
        val shortMessages = appWiring.gitClient.getShortMessages(listOf(ref))
        val message = shortMessages[ref]
        if (message != null) {
            renderer.info { "Stack '${entity(name)}' -> ${commitSubject(message)}" }
        }

        // Prompt for confirmation
        val terminal = currentContext.terminal
        val input = terminal.prompt("Delete stack '${name}'? [y/n]")?.trim()?.lowercase()
        if (input != "y") {
            renderer.warn { "Aborted." }
            return
        }

        val affectedBranches = gitJaspr.deleteStack(name, target)
        renderer.info { success("Deleted stack '${entity(name)}'.") }
        if (affectedBranches.isNotEmpty()) {
            for (branch in affectedBranches) {
                renderer.info { "Unset upstream for local branch '${entity(branch)}'." }
            }
        }
        renderer.info {
            "Note: PRs in the stack (if any) were not removed. " +
                "Run ${command("jaspr clean")} to remove them."
        }
    }
}

class PreviewTheme :
    GitJasprSubcommand(
        name = "preview-theme",
        helpText = "Preview the current theme with sample output",
    ) {
    override suspend fun doRun() {
        renderer.info { "Using theme ${entity(name)}." }
        val ident = Ident("Ada Lovelace", "ada@example.com")
        val now = ZonedDateTime.now()

        fun commit(hash: String, message: String, id: String) =
            Commit(hash, message, message, id, ident, ident, now, now)

        val commits =
            listOf(
                commit("a1b2c3d", "Add user authentication endpoint", "commit-1"),
                commit("e4f5a6b", "Validate auth tokens on protected routes", "commit-2"),
                commit("c7d8e9f", "Add rate limiting to auth endpoints", "commit-3"),
                commit("0a1b2c3", "Update API docs for auth flow", "commit-4"),
            )

        val prs = commits.mapIndexed { index, c ->
            PullRequest(
                id = "pr-${index + 1}",
                commitId = c.id,
                number = 100 + index,
                headRefName = "$DEFAULT_REMOTE_BRANCH_PREFIX/main/${c.id}",
                baseRefName =
                    if (index == 0) "main" else "$DEFAULT_REMOTE_BRANCH_PREFIX/main/commit-$index",
                title = c.shortMessage,
                body = "",
                checksPass =
                    when (index) {
                        3 -> null // top commit: checks pending
                        else -> true
                    },
                approved =
                    when {
                        index <= 1 -> true
                        else -> null
                    },
                permalink = "https://github.com/example/repo/pull/${100 + index}",
                isDraft = false,
            )
        }
        val prsByCommitId = prs.associateBy(PullRequest::commitId)

        val remoteBranches = commits.map { c ->
            RemoteBranch("$DEFAULT_REMOTE_BRANCH_PREFIX/main/${c.id}", c)
        }

        val queries =
            object : GitJaspr.StatusQueries {
                override fun getRemoteBranches() = remoteBranches

                override fun getCommitStack(localRef: String, remoteRef: String) = commits

                override fun logRange(since: String, until: String) = emptyList<Commit>()

                override fun getCommitIdsInRange(target: String, refs: List<String>) =
                    refs.associateWith {
                        emptyList<String>()
                    }

                override suspend fun getPullRequests(commits: List<Commit>) = commits.mapNotNull {
                    prsByCommitId[it.id]
                }
            }

        val dummyConfig =
            Config(
                workingDirectory = File("."),
                remoteName = "origin",
                gitHubInfo = GitHubInfo("github.com", "example", "repo"),
            )
        val dummyGitJaspr =
            GitJaspr(ghClient = unusedProxy(), gitClient = unusedProxy(), config = dummyConfig)

        print(dummyGitJaspr.getStatusString(theme = theme, queries = queries))
    }
}

/** Prints the path to today's telemetry log file. */
class LogPath : SuspendingCliktCommand(name = "log-path") {
    override fun help(context: Context) = "Print the path to today's log file"

    override fun helpEpilog(context: Context) = helpEpilog

    private val cliContext by requireObject<CliContext>()

    override suspend fun run() {
        val path = cliContext.logFilePath
        if (path != null) {
            echo(path)
        } else {
            echo("File logging is disabled.")
        }
    }
}

/** Generates a commented default config file in the user's home directory. */
class Init : SuspendingCliktCommand() {
    override fun help(context: Context) = "Generate a default config file"

    override fun helpEpilog(context: Context) = helpEpilog

    private val cliContext by requireObject<CliContext>()
    private val renderer
        get() = cliContext.renderer

    private val show by
        option("--show").flag().help { "Display the example config without writing it" }

    override suspend fun run() {
        if (show) {
            echo(readDefaultConfigResource())
            return
        }

        val homeDir = File(System.getenv("HOME"))
        val configFile = homeDir.resolve(CONFIG_FILE_NAME)
        val backupFile = homeDir.resolve("$CONFIG_FILE_NAME.bak")

        if (configFile.exists()) {
            if (backupFile.exists()) {
                renderer.run {
                    error {
                        "$configFile already exists and a backup ($backupFile) is also present."
                    }
                    error { "Please resolve manually before running init again." }
                }
                throw ProgramResult(1)
            }
            renderer.info { "${entity(configFile.absolutePath)} already exists." }
            val response =
                currentContext.terminal
                    .prompt(
                        "Overwrite? The existing file will be backed up to ${backupFile}. [y/N]"
                    )
                    ?.trim()
                    ?.lowercase()
            if (response != "y" && response != "yes") {
                renderer.info { "Aborted." }
                return
            }
            configFile.renameTo(backupFile)
            renderer.info { "Existing config backed up to ${entity(backupFile.toString())}" }
            echo()
        }

        // Carry over the github-token from the old config if present
        val oldConfig = homeDir.resolve(OLD_CONFIG_FILE_NAME)
        val content =
            if (oldConfig.exists()) {
                migrateConfig(oldConfig).also {
                    renderer.info {
                        "Found old config: ${entity(oldConfig.absolutePath)} (github-token carried over)"
                    }
                }
            } else {
                readDefaultConfigResource()
            }

        configFile.writeText(content)
        renderer.info { "Config file written to ${entity(configFile.toString())}" }
        if (content.contains(TOKEN_PLACEHOLDER)) {
            renderer.info {
                "Edit the file and add your GitHub personal access token to get started."
            }
        }
    }

    companion object {
        fun readDefaultConfigResource(): String =
            checkNotNull(Init::class.java.getResourceAsStream("/default-config.properties")) {
                    "default-config.properties resource not found"
                }
                .bufferedReader()
                .readText()
    }
}

// Used by tests
class NoOp : GitJasprSubcommand(helpText = "Do nothing", isHidden = true) {
    private val logger = LoggerFactory.getLogger(NoOp::class.java)

    override suspend fun doRun() {
        logger.info(commandName)
    }
}

// endregion

private const val TOKEN_PLACEHOLDER = "ghp_your_token_here"

/**
 * Reads the github-token from [oldConfig] and returns a new default config with the token
 * pre-filled. If no token is found (or it's the placeholder), returns the default config as-is.
 */
private fun migrateConfig(oldConfig: File): String {
    val oldProps = Properties().apply { oldConfig.reader().use(::load) }
    val token = oldProps.getProperty("github-token").orEmpty().ifEmpty { TOKEN_PLACEHOLDER }
    return Init.readDefaultConfigResource().replace(TOKEN_PLACEHOLDER, token)
}

fun File.findNearestGitDir(): File {
    val parentFiles = generateSequence(canonicalFile) { it.parentFile }
    return checkNotNull(parentFiles.firstOrNull { file -> file.resolve(".git").exists() }) {
        "Can't find a git working dir in $canonicalFile or any of its parent directories"
    }
}

object Cli {
    val logger: Logger = LoggerFactory.getLogger(Cli::class.java)

    @JvmStatic
    fun main(args: Array<out String>) {
        runBlocking { buildCommand().main(args) }
    }
}

fun buildCommand(): SuspendingCliktCommand =
    GitJasprRoot()
        .apply { installMordantMarkdown() }
        .versionOption(VERSION)
        .completionOption()
        .subcommands(
            // Stack workflow
            Status(),
            Compare(),
            Graph(),
            Pull(),
            Push(),
            Merge(),
            AutoMerge(),
            Clean(),
            Rebase(),
            Sync(),
            Checkout(),
            Stack().subcommands(StackList(), StackRename(), StackDelete()),
            // Editing
            Edit(),
            Edit(name = "reorder"),
            Fixup(),
            Split(),
            Unsplit(),
            Fold(),
            Continue(),
            // Navigation
            Down(),
            Up(),
            Bottom(),
            Top(),
            Goto(),
            Drop(),
            Nav().subcommands(NavCancel(), NavFinish()),
            // Configuration
            Init(),
            InstallHook(),
            PreviewTheme(),
            LogPath(),
            NoOp(),
        )

const val WORKING_DIR_PROPERTY_NAME = "git-jaspr-working-dir"
const val CONFIG_FILE_NAME = ".jaspr.properties"
const val OLD_CONFIG_FILE_NAME = ".git-jaspr.properties"
const val DEFAULT_LOCAL_OBJECT = GitClient.HEAD
const val DEFAULT_TARGET_REF = "main"
const val DEFAULT_REMOTE_NAME = "origin"
const val COMMIT_ID_LABEL = "commit-id"
private const val GITHUB_TOKEN_ENV_VAR = "GIT_JASPR_TOKEN"
const val JASPR_NO_UPDATE_CHECK_ENV_VAR = "JASPR_NO_UPDATE_CHECK"

/**
 * Environment variables we treat as "running under CI." Any of these set to a non-empty value
 * suppresses the update-check notice — those logs are not interactive.
 */
private val CI_ENV_VARS =
    listOf("CI", "GITHUB_ACTIONS", "GITLAB_CI", "BUILDKITE", "CIRCLECI", "TRAVIS", "JENKINS_URL")

/** Completes with unique remote branch names (remote prefix stripped). */
private val remoteBranchCandidates =
    CompletionCandidates.Custom.fromStdout(
        "git branch -r --format='%(refname:short)' | sed 's|^[^/]*/||' | sort -u"
    )

/** Result of attempting an fzf-based selection. */
private sealed interface FzfResult<out T> {
    /** fzf is not installed. */
    data object NotAvailable : FzfResult<Nothing>

    /** The user made a selection. */
    data class Selected<T>(val value: T) : FzfResult<T>

    /**
     * The user typed a query that matched none of the candidates and hit Enter. Only produced when
     * [fzfSelect] is called with `acceptTyped = true`.
     */
    data class Typed(val query: String) : FzfResult<Nothing>

    /** The user canceled (Esc / Ctrl-C). */
    data object Cancelled : FzfResult<Nothing>
}

/**
 * Presents [items] in fzf for interactive fuzzy selection. [displayLine] produces the visible
 * (possibly ANSI-colored) representation of each item; [key] returns a stable, plain-text
 * identifier used for round-tripping the selection back from fzf's stdout.
 *
 * Implementation note: each input line is `"<key>\t<displayLine>"`. fzf is told to display only
 * field 2+ via `--with-nth=2..`, while still echoing the full line on selection. We recover the
 * chosen item by parsing the leading key field, which is immune to whatever fzf does to the display
 * portion (ANSI stripping, truncation, etc.). Inside [previewCommand], `{1}` refers to the key.
 *
 * Keys must be unique across [items] and must not contain a tab character.
 */
private fun <T> fzfSelect(
    items: List<T>,
    displayLine: (T) -> String,
    key: (T) -> String,
    header: String? = null,
    previewCommand: String? = null,
    acceptTyped: Boolean = false,
): FzfResult<T> {
    val logger = Cli.logger
    if (items.isEmpty()) {
        logger.debug("fzfSelect: items empty -> Cancelled")
        return FzfResult.Cancelled
    }
    val fzfPath =
        try {
            ProcessBuilder("which", "fzf").redirectErrorStream(true).start().let { proc ->
                val path = proc.inputStream.bufferedReader().readLine()?.trim()
                if (proc.waitFor() == 0) path else null
            }
        } catch (_: Exception) {
            null
        } ?: return FzfResult.NotAvailable.also { logger.debug("fzfSelect: fzf not on PATH") }

    val keys = items.map(key)
    require(keys.none { it.contains('\t') }) { "fzfSelect keys must not contain tab characters" }
    val inputLines = items.indices.map { i -> "${keys[i]}\t${displayLine(items[i])}" }
    val command = buildList {
        add(fzfPath)
        add("--ansi")
        add("--height=~${items.size + 2}")
        add("--reverse")
        add("--delimiter=\t")
        add("--with-nth=2..")
        if (acceptTyped) add("--print-query")
        if (header != null) {
            add("--header=$header")
        }
        if (previewCommand != null) {
            add("--preview=$previewCommand")
        }
    }
    return try {
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process.outputStream.bufferedWriter().use { writer ->
            for (line in inputLines) {
                writer.write(line)
                writer.newLine()
            }
        }
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        // With --print-query, fzf writes two lines: the query on line 1 and the selected line on
        // line 2 (the latter empty if no match was selected). Without it, only the selected line.
        val lines = output.split('\n')
        val (query, selected) =
            if (acceptTyped) Pair(lines.getOrNull(0).orEmpty(), lines.getOrNull(1))
            else Pair("", lines.getOrNull(0))
        when (exitCode) {
            0 -> {
                val selectedKey = selected?.takeIf(String::isNotEmpty)?.substringBefore('\t')
                val index = selectedKey?.let(keys::indexOf) ?: -1
                if (index >= 0) {
                    FzfResult.Selected(items[index])
                } else {
                    logger.debug("fzfSelect: selected key {} did not match any item", selectedKey)
                    FzfResult.Cancelled
                }
            }
            1 ->
                if (acceptTyped && query.isNotEmpty()) FzfResult.Typed(query)
                else FzfResult.Cancelled
            else -> FzfResult.Cancelled
        }
    } catch (_: Exception) {
        FzfResult.NotAvailable
    }
}

/** Pipes [lines] through the user's pager (`$PAGER`, defaulting to `less -RF`). */
private fun printPaged(lines: List<String>) {
    val pagerCommand =
        System.getenv("PAGER")?.trim()?.takeIf(String::isNotEmpty)?.split("\\s+".toRegex())
            ?: listOf("less", "-RF")
    try {
        val process =
            ProcessBuilder(pagerCommand)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        process.outputStream.bufferedWriter().use { writer ->
            for (line in lines) {
                writer.write(line)
                writer.newLine()
            }
        }
        process.waitFor()
    } catch (_: Exception) {
        lines.forEach(::println)
    }
}

private const val helpEpilog =
    "Options can also be set in ~/$CONFIG_FILE_NAME or ./$CONFIG_FILE_NAME.\n" +
        "Run 'jaspr init' to generate a commented example config file."

/** Creates a JDK proxy that throws [UnsupportedOperationException] on any method call. */
private inline fun <reified T : Any> unusedProxy(): T {
    val clazz = T::class.java
    return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, m, _ ->
        error("${clazz.simpleName}.${m.name} should not be called")
    } as T
}
