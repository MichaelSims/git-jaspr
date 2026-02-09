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
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.sources.ChainedValueSource
import com.github.ajalt.clikt.sources.PropertiesValueSource
import com.github.ajalt.clikt.sources.ValueSource.Companion.getKey
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextColors.Companion.rgb
import com.github.ajalt.mordant.rendering.TextColors.brightWhite
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.intellij.lang.annotations.Language
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_NAMED_STACK_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteNamedStackRef

// region Commands
class Status :
    GitJasprCommand(
        help =
            // language=Markdown
            """
            Show status of current stack

            | Heading       | Description                                                                                                                                                                                                                           |
            |---------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
            | commit pushed | The commit has been pushed to the remote.                                                                                                                                                                                             |
            | exists        | A pull request has been created for the given commit.                                                                                                                                                                                 |
            | checks pass   | Github checks pass.                                                                                                                                                                                                                   |
            | ready         | The PR is ready for review, which means it is not a draft. Commits with subjects that begin with `DRAFT` or `WIP` will automatically be created in draft mode. Use the GitHub UI to mark the PR as ready for review when appropriate. |
            | approved      | The PR is approved.                                                                                                                                                                                                                   |
            | stack check   | This commit is mergeable, as well as all of its parent commits in the stack.                                                                                                                                                          |

            """
                .trimIndent()
    ) {
    override suspend fun doRun() {
        print(appWiring.gitJaspr.getStatusString(refSpec))
    }
}

class Push : GitJasprCommand(help = "Push local commits to the remote and open PRs for each one") {
    private val name by
        option()
            .help {
                "The \"friendly\" name of the stack. If provided, HEAD will be force-pushed to <prefix>/<name> " +
                    "where <prefix> is determined by ${remoteNamedStackBranchPrefixDelegate.names.single()} and the " +
                    "current branch's upstream will be set to this ref."
            }
            .convert { value -> StackNameGenerator.generateName(value.trim()) }
            .validate { value ->
                if (value.isEmpty()) {
                    fail("Stack name must contain at least one alphanumeric character")
                }
            }

    private val count by
        option("-c", "--count").int().help {
            "Limit the number of commits to push from the bottom of the stack. " +
                "A positive value pushes that many commits. " +
                "A negative value excludes that many commits from the top. " +
                "Mutually exclusive with ${localDelegate.names.joinToString("/")}."
        }

    override suspend fun doRun() {
        requireCountLocalExclusive(count, local)
        val jaspr = appWiring.gitJaspr
        val effectiveName =
            name
                ?: jaspr.suggestStackName(refSpec)?.let { suggested ->
                    promptForStackName(suggested)
                }
        jaspr.push(refSpec, stackName = effectiveName, count = count)
    }

    private fun promptForStackName(suggested: String): String {
        echo(
            "Please provide a name for your stack or press enter to accept the generated one " +
                "(in the future you can use the ${bold("--name")} option if you prefer)."
        )
        val terminal = currentContext.terminal
        var default = suggested
        while (true) {
            val input = terminal.prompt("Stack name", default = default.ifEmpty { null }) ?: default
            val normalized = StackNameGenerator.generateName(input)
            if (normalized.isEmpty()) {
                echo(red("Stack name must contain at least one alphanumeric character."))
                default = ""
                continue
            }
            if (normalized == input) return input
            echo("Normalized to: ${cyan(normalized)}")
            default = normalized
        }
    }
}

class Merge : GitJasprCommand(help = "Merge all local commits that are mergeable") {
    private val count by
        option("-c", "--count").int().help {
            "Limit the number of commits to merge from the bottom of the stack. " +
                "A positive value includes that many commits. " +
                "A negative value excludes that many commits from the top. " +
                "Mutually exclusive with ${localDelegate.names.joinToString("/")}."
        }

    override suspend fun doRun() {
        requireCountLocalExclusive(count, local)
        appWiring.gitJaspr.merge(refSpec, count = count)
    }
}

class AutoMerge :
    GitJasprCommand(help = "Poll GitHub until all local commits are mergeable, then merge them") {
    private val count by
        option("-c", "--count").int().help {
            "Limit the number of commits to auto-merge from the bottom of the stack. " +
                "A positive value includes that many commits. " +
                "A negative value excludes that many commits from the top. " +
                "Mutually exclusive with ${localDelegate.names.joinToString("/")}."
        }

    private val interval by
        option("--interval", "-i").int().default(10).help {
            "Polling interval in seconds. Setting this too low may exhaust GitHub rate limiting"
        }

    override suspend fun doRun() {
        requireCountLocalExclusive(count, local)
        appWiring.gitJaspr.autoMerge(refSpec, interval, count = count)
    }
}

class Clean : GitJasprCommand(help = "Clean up orphaned jaspr branches") {
    override suspend fun doRun() {
        val jaspr = appWiring.gitJaspr
        val terminal = currentContext.terminal
        var cleanAbandonedPrs = appWiring.config.cleanAbandonedPrs
        var cleanJustMyPrs = !appWiring.config.cleanAllCommits

        while (true) {
            echo("Finding branches to clean (this may take a minute)...")
            val plan =
                jaspr.getCleanPlan(
                    cleanAbandonedPrs = cleanAbandonedPrs,
                    cleanAllCommits = !cleanJustMyPrs,
                )
            displayPlan(plan, jaspr)

            if (plan.allBranches().isEmpty()) {
                echo(green("Nothing to clean."))
                return
            }

            fun onOff(v: Boolean) {
                if (v) green("on") else red("off")
            }

            echo()
            echo("Options:")
            echo("  [${bold("a")}] Clean abandoned PRs: ${onOff(cleanAbandonedPrs)}")
            echo("  [${bold("m")}] Clean just my PRs: ${onOff(cleanJustMyPrs)}")
            echo()

            val prompt =
                "Perform [${bold("c")}]lean, toggle [${bold("a")}]bandoned, " +
                    "toggle [${bold("m")}]ine, or [${bold("q")}]uit"
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
                    echo(green("Deleted $count ${if (count == 1) "branch" else "branches"}."))
                    return
                }

                "a" -> cleanAbandonedPrs = !cleanAbandonedPrs
                "m" -> cleanJustMyPrs = !cleanJustMyPrs
                "q",
                null -> {
                    echo(TextColors.yellow("Aborted."))
                    return
                }

                else -> echo(red("Invalid selection."))
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
                add(bold("Orphaned branches (PRs are closed or do not exist):"))
                for (branch in plan.orphanedBranches) {
                    val message = shortMessages[branch]?.let { " ${brightWhite(it)}" }.orEmpty()
                    add("  ${cyan(branch)}$message")
                }
            }

            if (plan.emptyNamedStackBranches.isNotEmpty()) {
                add("")
                add(bold("Empty named stack branches (fully merged):"))
                for (branch in plan.emptyNamedStackBranches) {
                    add("  ${cyan(branch)}")
                }
            }

            if (plan.abandonedBranches.isNotEmpty()) {
                add("")
                add(bold("Abandoned branches (open PRs not reachable by any named stack):"))
                for (branch in plan.abandonedBranches) {
                    val message = shortMessages[branch]?.let { " ${brightWhite(it)}" }.orEmpty()
                    add("  ${cyan(branch)}$message")
                }
            }
        }

        currentContext.terminal.printPaged(lines, appWiring.config.pageSize)
    }
}

class Checkout : GitJasprCommand(help = "Check out an existing named stack") {
    private val name by
        option("-n", "--name").help {
            "The name of the stack to check out (skips interactive selection)"
        }

    override suspend fun doRun() {
        val gitJaspr = appWiring.gitJaspr
        val config = appWiring.config
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
                    append(
                        "Use ${targetDelegate.names.joinToString("/")} to specify a different target."
                    )
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
                    add(bold("Named stacks targeting $target:"))
                    for ((index, stack) in stacks.withIndex()) {
                        val ref = "${remoteName}/${stack.name()}"
                        val message = shortMessages[ref]?.let { " ${brightWhite(it)}" }.orEmpty()
                        add("  ${bold("${index + 1}.")} " + "[${cyan(stack.stackName)}]$message")
                    }
                }
                terminal.printPaged(lines, appWiring.config.pageSize)
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
            echo(red("Invalid selection. Please enter a number between 1 and ${stacks.size}."))
        }
    }
}

class InstallCommitIdHook :
    GitJasprCommand(help = "Install commit-msg hook that adds commit-id's to local commits") {
    override suspend fun doRun() {
        appWiring.gitJaspr.installCommitIdHook()
    }
}

// Used by tests
class NoOp : GitJasprCommand(help = "Do nothing", hidden = true) {
    private val logger = LoggerFactory.getLogger(NoOp::class.java)

    // TODO why is this here?
    //    @Suppress("unused") val extraArgs by argument().multiple() // Ignore extra args

    override suspend fun doRun() {
        logger.info(commandName)
    }
}

// endregion

private class GitHubOptions : OptionGroup(name = "GitHub Options") {
    val githubHost by
        option().help {
            "The GitHub host. This will be inferred by the remote URI if not specified."
        }
    val repoOwner by
        option().help {
            "The GitHub owner name. This will be inferred by the remote URI if not specified."
        }
    val repoName by
        option().help {
            "The GitHub repo name. This will be inferred by the remote URI if not specified."
        }
}

abstract class GitJasprCommand(help: String = "", hidden: Boolean = false) :
    CliktCommand(hidden = hidden, help = help, epilog = helpEpilog) {
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
            // Read all option values first from CONFIG_FILE_NAME in the user's home directory,
            // overridden by CONFIG_FILE_NAME in the working directory, overridden by any options
            // provided on the command line.
            valueSource =
                ChainedValueSource(
                    listOf(workingDirectory, File(System.getenv("HOME"))).map { dir ->
                        PropertiesValueSource.from(
                            dir.resolve(CONFIG_FILE_NAME),
                            // don't add subcommand names to keys, see block comment in the main
                            // entry point below
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
You need to create one with read:org, read:user, repo,
and user:email permissions and provide it via one of
the following methods:

- Create a file named $CONFIG_FILE_NAME in your home
  directory with the following contents:
    github-token=<your token here>
- Create a file named $CONFIG_FILE_NAME in your working
  directory with the following contents:
    github-token=<your token here>
- Set the environment variable $GITHUB_TOKEN_ENV_VAR to
  your token

⚠️  NOTE ⚠️ : Please remember to enable SSO on your token 
if applicable. If in the future you change the scope of an
existing token, it will disable the SSO authorization so
you'll need to re-enable it again.
    """
            .trimIndent()

    private val githubToken by
        option(envvar = GITHUB_TOKEN_ENV_VAR)
            .transformAll(showAsRequired = true) { stringList ->
                stringList.lastOrNull()
                    ?: throw PrintMessage(
                        message = missingTokenMessage,
                        statusCode = 1,
                        printError = true,
                    )
            }
            .help {
                """
        A GitHub PAT (personal access token) with read:org, read:user, repo, and user:email permissions. Can be provided 
        via the per-user config file, a per-working copy config file, or the environment variable 
        $GITHUB_TOKEN_ENV_VAR.
            """
                    .trimIndent()
            }

    private val gitHubOptions by GitHubOptions()

    private val logLevel: Level by
        option("-L", "--log-level")
            .choice(
                *listOf(OFF, ERROR, WARN, INFO, DEBUG, TRACE, ALL)
                    .map { level -> level.levelStr to level }
                    .toTypedArray(),
                ignoreCase = true,
            )
            .default(INFO)
            .help { "The log level for the application." }

    private val logToFilesDelegate: OptionWithValues<Boolean, Boolean, Boolean> =
        option().flag("--no-log-to-files", default = true).help {
            "Write trace logs to directory specified by the ${logsDirectoryDelegate.names.first()} option"
        }

    private val logsDirectoryDelegate: OptionWithValues<File, File, File> =
        option().file().default(File("${System.getProperty("java.io.tmpdir")}/jaspr")).help {
            "Trace logs will be written into this directory if ${logToFilesDelegate.names.first()} is enabled"
        }

    private val logToFiles: Boolean by logToFilesDelegate
    private val logsDirectory: File by logsDirectoryDelegate

    val targetDelegate =
        option("-t", "--target").default(DEFAULT_TARGET_REF).help {
            "The name of the target branch on the remote."
        }
    val target by targetDelegate

    val localDelegate =
        option("-l", "--local").default(DEFAULT_LOCAL_OBJECT).help {
            "The local git object (branch, commit, etc.) to use as the source."
        }
    val local by localDelegate

    val refSpec
        get() = RefSpec(local, target)

    private val remoteBranchPrefix by
        option()
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

    val remoteNamedStackBranchPrefixDelegate =
        option()
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
    private val remoteNamedStackBranchPrefix by remoteNamedStackBranchPrefixDelegate

    private val dontPushRegex by
        option().default("^(dont[ -]?push)\\b.*$").help {
            "Regular expression pattern (case-insensitive) to match commit subjects that should not be pushed. " +
                "When pushing or merging, commits matching this pattern and all commits above them in the stack " +
                "will be excluded."
        }

    private val cleanAbandonedPrs by
        option().flag("--ignore-abandoned-prs", default = true).help {
            "When enabled, the clean command will also close open PRs for jaspr branches that are not " +
                "reachable by any existing named stack branch, and then delete those branches."
        }

    private val cleanAllCommits by
        option().flag("--clean-only-my-commits", default = false).help {
            "When enabled, the clean command will remove branches regardless of who authored the commits. " +
                "By default, only branches with commits authored by the current user will be removed."
        }

    private val pageSize by
        option().int().default(DEFAULT_PAGE_SIZE).help {
            "Number of lines to display per page in interactive output before prompting to continue."
        }

    private val showConfig by
        option(hidden = true).flag("--no-show-config", default = false).help {
            "Print the effective configuration to standard output (for debugging)"
        }

    private val remoteName by
        option("-r", "--remote-name")
            .help {
                """
                The name of the git remote. This is used for git operations and for inferring github information if not
                explicitly configured.
                """
                    .trimIndent()
            }
            .default(DEFAULT_REMOTE_NAME)

    val appWiring by lazy {
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
                cleanAbandonedPrs,
                cleanAllCommits,
                pageSize,
            )

        DefaultAppWiring(githubToken, config, gitClient)
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

    override fun run() {
        val logger = Cli.logger
        val config =
            try {
                appWiring.config
            } catch (e: Exception) {
                logger.debug("Initialization failed", e)
                printError(e)
            }
        if (showConfig) {
            throw PrintMessage(appWiring.json.encodeToString(config))
        }
        val (loggingContext, logFile) = initLogging(config.logLevel, config.logsDirectory)
        runBlocking {
            logger.debug("{} version {}", GitJaspr::class.java.simpleName, VERSION)
            try {
                doRun()
            } catch (e: GitJasprException) {
                logger.debug("An error occurred", e)
                printError(e)
            } catch (e: Exception) {
                logger.logUnhandledException(e, logFile)
                printError(e)
            } finally {
                logger.trace("Closing appWiring")
                appWiring.close()
                logger.trace("Stopping logging context")
                loggingContext.stop()
            }
        }
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

    private fun printError(e: Exception): Nothing =
        throw PrintMessage(e.message.orEmpty(), 255, true)

    private fun Logger.logUnhandledException(exception: Exception, logFile: String?) {
        error(exception.message, exception)
        error(
            "We're sorry, but you've likely encountered a bug. " +
                if (logFile != null) {
                    "Please open a bug report and attach the log file ($logFile)."
                } else {
                    "Please consider enabling file logging (see the ${logToFilesDelegate.names.first()} " +
                        "and ${logsDirectoryDelegate.names.first()} options) and opening a bug report " +
                        "with the log file attached."
                }
        )
    }

    protected fun requireCountLocalExclusive(count: Int?, local: String) {
        require(count == null || local == DEFAULT_LOCAL_OBJECT) {
            "The --count and --local options are mutually exclusive."
        }
    }

    abstract suspend fun doRun()
}

internal fun File.findNearestGitDir(): File {
    val parentFiles = generateSequence(canonicalFile) { it.parentFile }
    // Check for the first parent directory that contains a ".git" subdirectory (normal working dir)
    // or file (worktree working dir).
    return checkNotNull(parentFiles.firstOrNull { file -> file.resolve(".git").exists() }) {
        "Can't find a git working dir in $canonicalFile or any of its parent directories"
    }
}

object Cli {
    val logger: Logger = LoggerFactory.getLogger(Cli::class.java)

    @JvmStatic
    fun main(args: Array<out String>) {
        // NoOpCliktCommand is used as a parent container for the subcommands. Common options and
        // bootstrap code for the subcommands are handled via the GitJasprCommand abstract base
        // class.
        NoOpCliktCommand(name = "git jaspr")
            .versionOption(VERSION)
            .subcommands(
                listOf(
                    Status(),
                    Push(),
                    Checkout(),
                    Merge(),
                    AutoMerge(),
                    Clean(),
                    InstallCommitIdHook(),
                    NoOp(),
                )
            )
            .main(args)
    }
}

const val VERSION = "v1.0-beta-3"
const val WORKING_DIR_PROPERTY_NAME = "git-jaspr-working-dir"
const val CONFIG_FILE_NAME = ".git-jaspr.properties"
const val DEFAULT_LOCAL_OBJECT = GitClient.HEAD
const val DEFAULT_TARGET_REF = "main"
const val DEFAULT_REMOTE_NAME = "origin"
const val COMMIT_ID_LABEL = "commit-id"
private const val GITHUB_TOKEN_ENV_VAR = "GIT_JASPR_TOKEN"

/** Prints [lines] to the terminal, pausing every [pageSize] lines to prompt for continuation. */
private fun Terminal.printPaged(lines: List<String>, pageSize: Int = DEFAULT_PAGE_SIZE) {
    for ((index, line) in lines.withIndex()) {
        println(line)
        if (index > 0 && index < lines.lastIndex && (index + 1) % pageSize == 0) {
            val input =
                prompt("-- [${bold("n")}]ext page, [${bold("s")}]kip --")?.trim()?.lowercase()
            if (input != "n" && input != "") break
        }
    }
}

// Note the embedded color below matches what Clikt uses for section headings
@Language("Markdown")
private val helpEpilog =
    """
**${rgb("#E5C07B").invoke("Note on supplying config options via configuration files")}**

Any option above can be supplied via the per-user config file ($CONFIG_FILE_NAME in your home directory) or the per-working copy config file ($CONFIG_FILE_NAME in your working directory). For example, you can supply the --log-level option in the config file like so:

```
log-level=WARN
```
"""
        .trimIndent()
