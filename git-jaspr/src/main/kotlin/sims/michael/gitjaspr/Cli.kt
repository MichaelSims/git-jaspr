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
import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.sources.ChainedValueSource
import com.github.ajalt.clikt.sources.PropertiesValueSource
import com.github.ajalt.clikt.sources.ValueSource.Companion.getKey
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File
import java.lang.reflect.Proxy
import java.time.ZonedDateTime
import java.util.Properties
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteNamedStackRef

// region Option Groups

class TargetRefOptions : OptionGroup() {
    val target by
        option("-t", "--target").default(DEFAULT_TARGET_REF).help { "Target branch on the remote" }
    val local by
        option("-l", "--local").default(DEFAULT_LOCAL_OBJECT).help {
            "Local branch or commit that is the HEAD of the current stack"
        }
    val refSpec
        get() = RefSpec(local, target)
}

class TargetOptions : OptionGroup() {
    val target by
        option("-t", "--target").default(DEFAULT_TARGET_REF).help { "Target branch on the remote" }
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

/**
 * Wraps [Theme] and [Renderer] (UI concerns) alongside [AppWiring] (business-logic DI) in the Clikt
 * context.
 */
class CliContext(val theme: Theme, val renderer: Renderer, appWiringFactory: () -> AppWiring) {
    val appWiring by lazy(appWiringFactory)
}

/**
 * Root command that owns infrastructure options and passes [CliContext] to subcommands via context.
 * Subcommands access it via `requireObject<CliContext>()`.
 */
class GitJasprRoot : CliktCommand(name = "jaspr", epilog = helpEpilog) {
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

    private val remoteName by
        option("-r", "--remote-name").help { "Git remote name" }.default(DEFAULT_REMOTE_NAME)

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

    private val theme by
        option("--theme").default("default").help {
            "Terminal theme (default, mono, or a custom name)"
        }

    private fun buildAppWiring(renderer: Renderer): AppWiring {
        val token =
            githubToken
                ?: run {
                    renderer.error { missingTokenMessage }
                    throw ProgramResult(1)
                }
        val gitClient = OptimizedCliGitClient(workingDirectory, remoteBranchPrefix)
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
            )
        return DefaultAppWiring(token, config, gitClient, renderer)
    }

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

    override fun run() {
        val logger = Cli.logger
        val (loggingContext, _) = initLogging(logLevel, logsDirectory.takeIf { logToFiles })
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
            CliContext(resolvedTheme, renderer) {
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
    help: String = "",
    hidden: Boolean = false,
) : CliktCommand(name = name, hidden = hidden, help = help, epilog = helpEpilog) {

    private val cliContext by requireObject<CliContext>()
    val appWiring
        get() = cliContext.appWiring

    val theme
        get() = cliContext.theme

    val renderer
        get() = cliContext.renderer

    abstract suspend fun doRun()

    override fun run() {
        val logger = Cli.logger
        runBlocking {
            try {
                doRun()
            } catch (e: GitJasprException) {
                logger.debug("An error occurred", e)
                renderer.error { e.message }
                throw ProgramResult(255)
            } catch (e: Exception) {
                logger.logUnhandledException(e)
                renderer.error { e.message.orEmpty() }
                throw ProgramResult(255)
            } finally {
                logger.trace("Closing appWiring")
                appWiring.close()
            }
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

class Status :
    GitJasprSubcommand(
        help =
            // language=Markdown
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
    ) {
    private val targetRef by TargetRefOptions()

    override suspend fun doRun() {
        print(appWiring.gitJaspr.getStatusString(targetRef.refSpec, theme))
    }
}

class Push : GitJasprSubcommand(help = "Push commits and create/update PRs") {
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
        val jaspr = appWiring.gitJaspr

        fun promptForNameIfNecessary(): String? =
            jaspr.suggestStackName(targetRef.refSpec)?.let { suggested ->
                promptForStackName(suggested)
            }

        val effectiveName = name ?: promptForNameIfNecessary()
        jaspr.push(
            targetRef.refSpec,
            stackName = effectiveName,
            count = count,
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

class Merge : GitJasprSubcommand(help = "Merge all mergeable commits") {
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

class AutoMerge : GitJasprSubcommand(help = "Wait for checks then merge") {
    private val targetRef by TargetRefOptions()

    private val count by
        option("-c", "--count").int().help {
            "Limit commits from bottom of stack (negative excludes from top)"
        }

    private val interval by
        option("--interval", "-i").int().default(10).help { "Polling interval in seconds" }

    override suspend fun doRun() {
        requireCountLocalExclusive(count, targetRef.local)
        appWiring.gitJaspr.autoMerge(targetRef.refSpec, interval, count = count)
    }
}

class Clean : GitJasprSubcommand(help = "Clean up orphaned branches") {
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
            displayPlan(plan, jaspr)

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

                "a" -> cleanAbandonedPrs = !cleanAbandonedPrs
                "m" -> cleanJustMyPrs = !cleanJustMyPrs
                "q",
                null -> {
                    renderer.warn { "Aborted." }
                    return
                }

                else -> renderer.error { "Invalid selection." }
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

class Checkout : GitJasprSubcommand(help = "Check out an existing named stack") {
    private val targetOpts by TargetOptions()

    private val name by option("-n", "--name").help { "Stack name (skips interactive selection)" }

    override suspend fun doRun() {
        val gitJaspr = appWiring.gitJaspr
        val config = appWiring.config
        val target = targetOpts.target
        val allStacks = gitJaspr.getAllNamedStacks()
        val stacks = allStacks.filter { it.targetRef == target }
        if (stacks.isEmpty()) {
            val message = buildString {
                append(
                    "No named stacks found targeting '%s' (searching %s/%s/*)."
                        .format(target, config.remoteNamedStackBranchPrefix, target)
                )
                val otherStacks = allStacks.filter { it.targetRef != target }
                if (otherStacks.isNotEmpty()) {
                    appendLine()
                    appendLine("Named stacks exist for other targets:")
                    for (stack in otherStacks.take(5)) {
                        appendLine("  [${stack.targetRef}] ${stack.stackName}")
                    }
                    if (otherStacks.size > 5) {
                        appendLine("  ... and ${otherStacks.size - 5} more")
                    }
                    append("Use -t/--target to specify a different target.")
                }
            }
            throw GitJasprException(message)
        }

        val selected =
            if (name != null) {
                stacks.find { it.stackName == name }
                    ?: throw GitJasprException(
                        "No named stack '$name' found targeting '$target'. " +
                            "Available stacks: ${stacks.joinToString(", ") { it.stackName }}"
                    )
            } else {
                val remoteName = config.remoteName
                val refs = stacks.map { "${remoteName}/${it.name()}" }
                val shortMessages = appWiring.gitClient.getShortMessages(refs)
                val terminal = currentContext.terminal
                val lines = buildList {
                    add(theme.heading("Named stacks targeting $target:"))
                    for ((index, stack) in stacks.withIndex()) {
                        val ref = "${remoteName}/${stack.name()}"
                        val message =
                            shortMessages[ref]?.let { " ${theme.commitSubject(it)}" }.orEmpty()
                        add(
                            "  ${theme.keyHint("${index + 1}.")} " +
                                "[${theme.entity(stack.stackName)}]$message"
                        )
                    }
                }
                printPaged(lines)
                promptForSelection(terminal, stacks)
            }

        gitJaspr.checkoutNamedStack(selected)
    }

    private fun promptForSelection(
        terminal: Terminal,
        stacks: List<RemoteNamedStackRef>,
    ): RemoteNamedStackRef {
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

class InstallCommitIdHook : GitJasprSubcommand(help = "Install commit-id hook") {
    override suspend fun doRun() {
        appWiring.gitJaspr.installCommitIdHook()
    }
}

class Stack : CliktCommand(name = "stack", help = "Manage named stacks") {
    override fun run() = Unit
}

class StackList : GitJasprSubcommand(name = "list", help = "List all named stacks") {

    override suspend fun doRun() {
        val gitJaspr = appWiring.gitJaspr
        val config = appWiring.config
        val allStacks = gitJaspr.getAllNamedStacks()

        if (allStacks.isEmpty()) {
            renderer.info { "No named stacks found." }
            return
        }

        val remoteName = config.remoteName
        val refs = allStacks.map { ref -> "${remoteName}/${ref.name()}" }
        val shortMessages = appWiring.gitClient.getShortMessages(refs)

        val stacksByTarget = allStacks.groupBy(RemoteNamedStackRef::targetRef)
        val lines = buildList {
            for ((targetRef, stacks) in stacksByTarget) {
                add(theme.heading("Stacks targeting $targetRef:"))
                for (stack in stacks) {
                    val ref = "${remoteName}/${stack.name()}"
                    val message =
                        shortMessages[ref]?.let { " ${theme.commitSubject(it)}" }.orEmpty()
                    add("  [${theme.entity(stack.stackName)}]$message")
                }
                add("")
            }
        }
        printPaged(lines)
    }
}

class StackRename : GitJasprSubcommand(name = "rename", help = "Rename a named stack") {
    private val targetOpts by TargetOptions()

    private val oldName by argument(help = "The current name of the stack")
    private val newName by
        argument(help = "The new name for the stack").convert { value ->
            StackNameGenerator.generateName(value.trim())
        }

    override suspend fun doRun() {
        if (newName.isEmpty()) {
            throw GitJasprException(
                "New stack name must contain at least one alphanumeric character."
            )
        }
        appWiring.gitJaspr.renameStack(oldName, newName, targetOpts.target)
        renderer.info { success("Renamed stack '${entity(oldName)}' to '${entity(newName)}'.") }
    }
}

class StackDelete :
    GitJasprSubcommand(name = "delete", help = "Delete a named stack from the remote") {
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
        help = "Preview the current theme with sample output",
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

        val prs =
            commits.mapIndexed { index, c ->
                PullRequest(
                    id = "pr-${index + 1}",
                    commitId = c.id,
                    number = 100 + index,
                    headRefName = "$DEFAULT_REMOTE_BRANCH_PREFIX/main/${c.id}",
                    baseRefName =
                        if (index == 0) "main"
                        else "$DEFAULT_REMOTE_BRANCH_PREFIX/main/commit-$index",
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

        val remoteBranches =
            commits.map { c -> RemoteBranch("$DEFAULT_REMOTE_BRANCH_PREFIX/main/${c.id}", c) }

        val strategy =
            object : GitJaspr.GetStatusStringStrategy {
                override fun getRemoteBranches() = remoteBranches

                override fun getLocalCommitStack(localRef: String, remoteRef: String) = commits

                override fun logRange(since: String, until: String) = emptyList<Commit>()

                override suspend fun getPullRequests(commits: List<Commit>) =
                    commits.mapNotNull { prsByCommitId[it.id] }
            }

        val dummyConfig =
            Config(
                workingDirectory = File("."),
                remoteName = "origin",
                gitHubInfo = GitHubInfo("github.com", "example", "repo"),
            )
        val dummyGitJaspr =
            GitJaspr(ghClient = unusedProxy(), gitClient = unusedProxy(), config = dummyConfig)

        print(dummyGitJaspr.getStatusString(theme = theme, strategy = strategy))
    }
}

/** Generates a commented default config file in the user's home directory. */
class Init : CliktCommand(help = "Generate a default config file", epilog = helpEpilog) {

    private val cliContext by requireObject<CliContext>()
    private val renderer
        get() = cliContext.renderer

    private val theme
        get() = cliContext.theme

    private val show by
        option("--show").flag().help { "Display the example config without writing it" }

    override fun run() {
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
            renderer.info { "Existing config backed up to $backupFile" }
            echo()
        }

        // Carry over the github-token from the old config if present
        val oldConfig = homeDir.resolve(OLD_CONFIG_FILE_NAME)
        val content =
            if (oldConfig.exists()) {
                migrateConfig(oldConfig).also {
                    renderer.info {
                        "Found old config: ${oldConfig.absolutePath} (github-token carried over)"
                    }
                }
            } else {
                readDefaultConfigResource()
            }

        configFile.writeText(content)
        renderer.info { "Config file written to $configFile" }
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
class NoOp : GitJasprSubcommand(help = "Do nothing", hidden = true) {
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

internal fun File.findNearestGitDir(): File {
    val parentFiles = generateSequence(canonicalFile) { it.parentFile }
    return checkNotNull(parentFiles.firstOrNull { file -> file.resolve(".git").exists() }) {
        "Can't find a git working dir in $canonicalFile or any of its parent directories"
    }
}

object Cli {
    val logger: Logger = LoggerFactory.getLogger(Cli::class.java)

    @JvmStatic
    fun main(args: Array<out String>) {
        buildCommand().main(args)
    }
}

fun buildCommand(): CliktCommand =
    GitJasprRoot()
        .versionOption(VERSION)
        .completionOption()
        .subcommands(
            Status(),
            Push(),
            Checkout(),
            Merge(),
            AutoMerge(),
            Clean(),
            Stack().subcommands(StackList(), StackRename(), StackDelete()),
            PreviewTheme(),
            Init(),
            InstallCommitIdHook(),
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
