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
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextColors.brightWhite
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File
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
            "Local branch or commit to use as source"
        }
    val refSpec
        get() = RefSpec(local, target)
}

class TargetOptions : OptionGroup() {
    val target by
        option("-t", "--target").default(DEFAULT_TARGET_REF).help { "Target branch on the remote" }
}

class PagingOptions : OptionGroup() {
    val pageSize by
        option().int().default(DEFAULT_PAGE_SIZE).help { "Lines per page in interactive output" }
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
 * Root command that owns infrastructure options and passes [AppWiring] to subcommands via context.
 * Subcommands access it via `requireObject<AppWiring>()`.
 */
class GitJasprRoot : CliktCommand(name = "git jaspr", epilog = helpEpilog) {
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
        eagerOption("--help-config", help = "Show config file options and exit") {
            throw PrintMessage(buildConfigHelpText(context.command, context))
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

    private val dontPushRegex by
        option(hidden = true).default("^(dont[ -]?push)\\b.*$").help {
            "Regular expression pattern (case-insensitive) to match commit subjects that should not be pushed."
        }

    private val remoteName by
        option("-r", "--remote-name", hidden = true)
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

    private fun buildAppWiring(): AppWiring {
        val token =
            githubToken
                ?: throw PrintMessage(
                    message = missingTokenMessage,
                    statusCode = 1,
                    printError = true,
                )
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
        return DefaultAppWiring(token, config, gitClient)
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
        if (showConfig) {
            val appWiring = buildAppWiring()
            try {
                throw PrintMessage(appWiring.json.encodeToString(appWiring.config))
            } finally {
                appWiring.close()
            }
        }
        val (loggingContext, _) = initLogging(logLevel, logsDirectory.takeIf { logToFiles })
        try {
            currentContext.obj = buildAppWiring()
        } catch (e: Exception) {
            logger.debug("Initialization failed", e)
            loggingContext.stop()
            throw PrintMessage(e.message.orEmpty(), 255, true)
        }
        logger.debug("{} version {}", GitJaspr::class.java.simpleName, VERSION)
    }

    private fun initLogging(
        logLevel: Level,
        logFileDirectory: File?,
    ): Pair<LoggerContext, String?> {
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
}

private class GitHubOptions : OptionGroup() {
    val githubHost by option(hidden = true).help { "GitHub host (inferred from remote URI)" }
    val repoOwner by option(hidden = true).help { "GitHub owner name (inferred from remote URI)" }
    val repoName by option(hidden = true).help { "GitHub repo name (inferred from remote URI)" }
}

// endregion

// region Subcommand Base Class

/**
 * Thin base class for subcommands. Accesses [AppWiring] from the parent command's context.
 * Subclasses implement [doRun] as a suspend function.
 */
abstract class GitJasprSubcommand(
    name: String? = null,
    help: String = "",
    hidden: Boolean = false,
) : CliktCommand(name = name, hidden = hidden, help = help, epilog = helpEpilog) {

    val appWiring by requireObject<AppWiring>()

    abstract suspend fun doRun()

    override fun run() {
        val logger = Cli.logger
        runBlocking {
            try {
                doRun()
            } catch (e: GitJasprException) {
                logger.debug("An error occurred", e)
                throw PrintMessage(e.message.orEmpty(), 255, true)
            } catch (e: Exception) {
                logger.logUnhandledException(e)
                throw PrintMessage(e.message.orEmpty(), 255, true)
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
            Show status of current stack

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
        print(appWiring.gitJaspr.getStatusString(targetRef.refSpec))
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

    override suspend fun doRun() {
        requireCountLocalExclusive(count, targetRef.local)
        val jaspr = appWiring.gitJaspr
        val effectiveName =
            name
                ?: jaspr.suggestStackName(targetRef.refSpec)?.let { suggested ->
                    promptForStackName(suggested)
                }
        jaspr.push(targetRef.refSpec, stackName = effectiveName, count = count)
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
    private val targetOpts by TargetOptions()
    private val cleanOpts by CleanBehaviorOptions()
    private val pagingOpts by PagingOptions()

    override suspend fun doRun() {
        val jaspr = appWiring.gitJaspr
        val terminal = currentContext.terminal
        var cleanAbandonedPrs = cleanOpts.cleanAbandonedPrs
        var cleanJustMyPrs = !cleanOpts.cleanAllCommits

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

        currentContext.terminal.printPaged(lines, pagingOpts.pageSize)
    }
}

class Checkout : GitJasprSubcommand(help = "Check out an existing named stack") {
    private val targetOpts by TargetOptions()
    private val pagingOpts by PagingOptions()

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
                    add(bold("Named stacks targeting $target:"))
                    for ((index, stack) in stacks.withIndex()) {
                        val ref = "${remoteName}/${stack.name()}"
                        val message = shortMessages[ref]?.let { " ${brightWhite(it)}" }.orEmpty()
                        add("  ${bold("${index + 1}.")} " + "[${cyan(stack.stackName)}]$message")
                    }
                }
                terminal.printPaged(lines, pagingOpts.pageSize)
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

class InstallCommitIdHook : GitJasprSubcommand(help = "Install commit-id hook") {
    override suspend fun doRun() {
        appWiring.gitJaspr.installCommitIdHook()
    }
}

class Stack : CliktCommand(name = "stack", help = "Manage named stacks") {
    override fun run() = Unit
}

class StackList : GitJasprSubcommand(name = "list", help = "List all named stacks") {
    private val targetOpts by TargetOptions()
    private val pagingOpts by PagingOptions()

    override suspend fun doRun() {
        val gitJaspr = appWiring.gitJaspr
        val config = appWiring.config
        val allStacks = gitJaspr.getAllNamedStacks()

        if (allStacks.isEmpty()) {
            echo("No named stacks found.")
            return
        }

        val remoteName = config.remoteName
        val refs = allStacks.map { ref -> "${remoteName}/${ref.name()}" }
        val shortMessages = appWiring.gitClient.getShortMessages(refs)
        val terminal = currentContext.terminal

        val stacksByTarget = allStacks.groupBy(RemoteNamedStackRef::targetRef)
        val lines = buildList {
            for ((targetRef, stacks) in stacksByTarget) {
                add(bold("Stacks targeting $targetRef:"))
                for (stack in stacks) {
                    val ref = "${remoteName}/${stack.name()}"
                    val message = shortMessages[ref]?.let { " ${brightWhite(it)}" }.orEmpty()
                    add("  [${cyan(stack.stackName)}]$message")
                }
                add("")
            }
        }
        terminal.printPaged(lines, pagingOpts.pageSize)
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
        echo(green("Renamed stack '${cyan(oldName)}' to '${cyan(newName)}'."))
    }
}

class StackDelete :
    GitJasprSubcommand(name = "delete", help = "Delete a named stack from the remote") {
    private val targetOpts by TargetOptions()
    private val pagingOpts by PagingOptions()

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
            echo("Stack '${cyan(name)}' -> ${brightWhite(message)}")
        }

        // Prompt for confirmation
        val terminal = currentContext.terminal
        val input = terminal.prompt("Delete stack '${name}'? [y/n]")?.trim()?.lowercase()
        if (input != "y") {
            echo(TextColors.yellow("Aborted."))
            return
        }

        val affectedBranches = gitJaspr.deleteStack(name, target)
        echo(green("Deleted stack '${cyan(name)}'."))
        if (affectedBranches.isNotEmpty()) {
            for (branch in affectedBranches) {
                echo("Unset upstream for local branch '${cyan(branch)}'.")
            }
        }
        echo(
            "Note: PRs in the stack (if any) were not removed. " +
                "Run ${bold("git jaspr clean")} to remove them."
        )
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
        GitJasprRoot()
            .versionOption(VERSION)
            .subcommands(
                listOf(
                    Status(),
                    Push(),
                    Checkout(),
                    Merge(),
                    AutoMerge(),
                    Clean(),
                    Stack().subcommands(StackList(), StackRename(), StackDelete()),
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

private const val helpEpilog =
    "Options can also be set in ~/$CONFIG_FILE_NAME or ./$CONFIG_FILE_NAME (e.g. log-level=WARN)."

/** Walks the command tree and builds config help text from registered options. */
private fun buildConfigHelpText(root: CliktCommand, context: Context): String {
    data class ConfigEntry(val key: String, val help: String, val default: String?)

    val seen = mutableSetOf<String>()
    val entries = mutableListOf<ConfigEntry>()
    val excludedKeys = setOf("show-config")

    fun collectOptions(command: CliktCommand) {
        for (option in command.registeredOptions()) {
            if (option.eager || !option.hidden) continue
            val key =
                option.valueSourceKey
                    ?: option.names.maxByOrNull(String::length)?.removePrefix("--")
                    ?: continue
            if (key in seen || key in excludedKeys) continue
            seen.add(key)
            val help = option.optionHelp(context)
            val default =
                option.helpTags["default"]?.takeIf { default ->
                    // Exclude logs-directory default because it's a long value on macOS
                    default.isNotBlank() && key != "logs-directory"
                }
            entries.add(ConfigEntry(key, help, default))
        }
        for (sub in command.registeredSubcommands()) {
            collectOptions(sub)
        }
    }

    collectOptions(root)

    //    entries.sortBy { it.key }

    val maxKeyLen = entries.maxOf { it.key.length }
    val keyLines =
        entries
            //            .sortedBy { it.key }
            .joinToString("\n") { (key, help, default) ->
                val padding = " ".repeat(maxKeyLen - key.length + 2)
                val defaultSuffix = if (default != null) dim(" (default: $default)") else ""
                "  ${cyan(key)}$padding$help$defaultSuffix"
            }

    return buildString {
        appendLine(bold("Configuration File Options"))
        appendLine()
        appendLine("Options can be set in a Java properties file at either location:")
        appendLine("  ${cyan("~/$CONFIG_FILE_NAME")}   (user-wide defaults)")
        appendLine("  ${cyan("./$CONFIG_FILE_NAME")}   (per-repo overrides)")
        appendLine()
        appendLine("Per-repo values take precedence over user-wide values. CLI flags")
        appendLine("take precedence over both.")
        appendLine()
        appendLine(bold("Available keys:"))
        appendLine()
        appendLine(keyLines)
        appendLine()
        appendLine(bold("Example ~/$CONFIG_FILE_NAME:"))
        appendLine()
        appendLine("  ${dim("# A classic GitHub Personal Access Token")}")
        appendLine("  ${dim("# with read:org, read:user, repo, and user:email permissions.")}")
        appendLine("  ${dim("# Don't forget to enable SSO on your PAT, if applicable.")}")
        appendLine("  ${cyan("github-token")}=${green("ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")}")
        appendLine("  ${cyan("log-level")}=${green("WARN")}")
        append("  ${cyan("target")}=${green("develop")}")
    }
}
