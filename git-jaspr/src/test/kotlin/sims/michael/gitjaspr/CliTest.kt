package sims.michael.gitjaspr

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.zeroturnaround.exec.ProcessExecutor
import sims.michael.gitjaspr.ExecuteCli.executeCli
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import sims.michael.gitjaspr.testing.toStringWithClickableURI
import java.io.File
import java.lang.IllegalStateException
import java.nio.file.Files
import java.util.*
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CliTest {

    private val logger = LoggerFactory.getLogger(CliTest::class.java)

    @TestFactory
    fun `github info is correctly inferred from remote name and URI`(): List<DynamicTest> {
        fun test(name: String, remoteUri: String, remoteName: String, expected: GitHubInfo) = dynamicTest(name) {
            val scratchDir = createTempDir()
            try {
                val expectedConfig =
                    Config(scratchDir.repoDir(), remoteName, expected, logsDirectory = scratchDir.logsDir())
                val actual = getEffectiveConfigFromCli(
                    scratchDir,
                    remoteUri,
                    remoteName,
                    homeDirConfig = mapOf("remote-name" to remoteName),
                )
                assertEquals(ComparableConfig(expectedConfig), ComparableConfig(actual))
            } finally {
                scratchDir.deleteRecursively()
            }
        }
        return listOf(
            test("from origin", "git@github.com:owner/name.git", "origin", GitHubInfo("github.com", "owner", "name")),
            test("from other", "git@other.com:owner/name.git", "other", GitHubInfo("other.com", "owner", "name")),
        )
    }

    @Test
    fun `test config happy path`() {
        val scratchDir = createTempDir()
        val expected = config(
            workingDirectory = scratchDir.repoDir(),
            gitHubInfo = GitHubInfo(
                host = "github.com",
                owner = "SomeOwner",
                name = "some-repo-name",
            ),
            logsDirectory = scratchDir.logsDir(),
        )
        val actual = getEffectiveConfigFromCli(
            scratchDir,
            remoteUri = "git@github.com:SomeOwner/some-repo-name.git",
            remoteName = expected.remoteName,
        )
        assertEquals(ComparableConfig(expected), ComparableConfig(actual))
    }

    @Test
    fun `invoked from subdirectory finds repo`() {
        val scratchDir = createTempDir()
        // This will come from the configuration, the rest will be inferred by the URI
        val explicitlyConfiguredHost = "example.com"
        val expected = config(
            workingDirectory = scratchDir.repoDir(),
            gitHubInfo = GitHubInfo(
                host = explicitlyConfiguredHost,
                owner = "SomeOwner",
                name = "some-repo-name",
            ),
            logsDirectory = scratchDir.logsDir(),
        )
        val invokeLocation = scratchDir.repoDir().resolve("my/sub/dir")
            .also { it.mkdirs() }
        val actual = getEffectiveConfigFromCli(
            scratchDir,
            remoteUri = "git@github.com:SomeOwner/some-repo-name.git",
            remoteName = expected.remoteName,
            homeDirConfig = mapOf("github-host" to explicitlyConfiguredHost),
            invokeLocation = invokeLocation,
        )
        assertEquals(ComparableConfig(expected), ComparableConfig(actual))
    }

    @Test
    fun `fails on no git dir`() {
        val scratchDir = createTempDir()
        // This will come from the configuration, the rest will be inferred by the URI
        val explicitlyConfiguredHost = "example.com"
        val expected = config(
            workingDirectory = scratchDir.repoDir(),
            gitHubInfo = GitHubInfo(
                host = explicitlyConfiguredHost,
                owner = "SomeOwner",
                name = "some-repo-name",
            ),
            logsDirectory = scratchDir.logsDir(),
        )
        val invokeLocation = scratchDir.resolve("otherProject/")
            .also { it.mkdirs() }
        val thrownMessage = assertThrows<IllegalStateException> {
            executeCli(
                scratchDir,
                remoteUri = "git@github.com:SomeOwner/some-repo-name.git",
                remoteName = expected.remoteName,
                homeDirConfig = mapOf("github-host" to explicitlyConfiguredHost),
                strings = listOf("status"),
                invokeLocation = invokeLocation,
            )
        }.message
        assertNotNull(thrownMessage)
        assertContains(thrownMessage, "java.lang.IllegalStateException: Can't find a git dir")
    }

    @Test
    fun `gitHubInfo can be partially explicit and partially implicit`() {
        val scratchDir = createTempDir()
        // This will come from the configuration, the rest will be inferred by the URI
        val explicitlyConfiguredHost = "example.com"
        val expected = config(
            workingDirectory = scratchDir.repoDir(),
            gitHubInfo = GitHubInfo(
                host = explicitlyConfiguredHost,
                owner = "SomeOwner",
                name = "some-repo-name",
            ),
            logsDirectory = scratchDir.logsDir(),
        )
        val actual = getEffectiveConfigFromCli(
            scratchDir,
            remoteUri = "git@github.com:SomeOwner/some-repo-name.git",
            remoteName = expected.remoteName,
            homeDirConfig = mapOf("github-host" to explicitlyConfiguredHost),
        )
        assertEquals(ComparableConfig(expected), ComparableConfig(actual))
    }

    @Test
    fun `configuration priority is as expected`() {
        // CLI takes precedence over repo dir config file which takes precedence over home dir config file
        val scratchDir = createTempDir()
        val expected = config(
            workingDirectory = scratchDir.repoDir(),
            gitHubInfo = GitHubInfo(
                host = "hostFromHomeDir",
                owner = "ownerFromRepoDir",
                name = "nameFromCli",
            ),
            logsDirectory = scratchDir.logsDir(),
        )
        val actual = getEffectiveConfigFromCli(
            scratchDir,
            remoteUri = "git@example.com:SomeOwner/some-repo-name.git",
            remoteName = expected.remoteName,
            homeDirConfig = mapOf(
                "github-host" to "hostFromHomeDir",
                "repo-owner" to "ownerFromHomeDir",
                "repo-name" to "nameFromHomeDir",
            ),
            repoDirConfig = mapOf(
                "repo-owner" to "ownerFromRepoDir",
                "repo-name" to "nameFromRepoDir",
            ),
            extraCliArgs = listOf(
                "--repo-name",
                "nameFromCli",
            ),
        )
        assertEquals(ComparableConfig(expected), ComparableConfig(actual))
    }

    @Test
    fun `trace logs are written`() {
        val scratchDir = createTempDir()
        val expected = config(
            workingDirectory = scratchDir.repoDir(),
            gitHubInfo = GitHubInfo("host", "owner", "name"),
            logsDirectory = scratchDir.logsDir(),
        )
        executeCli(
            scratchDir,
            "git@host:owner/name.git",
            expected.remoteName,
            homeDirConfig = mapOf("logs-directory" to scratchDir.logsDir().absolutePath),
            strings = listOf("no-op"),
        )

        val lastLogFile = scratchDir.logsDir().walkTopDown().filter(File::isFile).toList().last()
        assertTrue(lastLogFile.readText().contains("[main]"), "$lastLogFile doesn't seem to be a log file")
    }

    @Test
    fun `install hook works as expected`() {
        val scratchDir = createTempDir()
        val expected = config(
            workingDirectory = scratchDir.repoDir(),
            gitHubInfo = GitHubInfo(
                host = "github.com",
                owner = "SomeOwner",
                name = "some-repo-name",
            ),
            logsDirectory = scratchDir.logsDir(),
        )
        executeCli(
            scratchDir,
            "git@host:owner/name.git",
            expected.remoteName,
            homeDirConfig = mapOf("logs-directory" to scratchDir.logsDir().absolutePath),
            strings = listOf("install-commit-id-hook"),
        )
        assertTrue(scratchDir.repoDir().resolve(".git").resolve("hooks").resolve("commit-msg").canExecute())
    }

    private fun config(workingDirectory: File, gitHubInfo: GitHubInfo, logsDirectory: File) = Config(
        workingDirectory,
        remoteName = DEFAULT_REMOTE_NAME,
        gitHubInfo,
        remoteBranchPrefix = DEFAULT_REMOTE_BRANCH_PREFIX,
        logsDirectory = logsDirectory,
    )

    private fun createTempDir(): File {
        val dir = checkNotNull(Files.createTempDirectory(CliTest::class.java.simpleName).toFile()).canonicalFile
        logger.info("Temp dir created in {}", dir.toStringWithClickableURI())
        return dir
    }
}

private fun getEffectiveConfigFromCli(
    scratchDir: File,
    remoteUri: String,
    remoteName: String,
    extraCliArgs: List<String> = emptyList(),
    homeDirConfig: Map<String, String> = emptyMap(),
    repoDirConfig: Map<String, String> = emptyMap(),
    invokeLocation: File? = null,
): Config = Json.decodeFromString(
    executeCli(
        scratchDir,
        remoteUri,
        remoteName,
        extraCliArgs,
        homeDirConfig,
        repoDirConfig,
        listOf("status", "--show-config", remoteName),
        invokeLocation,
    ).also { println(it) },
)

object ExecuteCli { // Wrapper object so we can have a logger with a sensible name
    private val logger = LoggerFactory.getLogger(ExecuteCli::class.java)

    fun executeCli(
        scratchDir: File,
        remoteUri: String,
        remoteName: String,
        extraCliArgs: List<String> = emptyList(),
        homeDirConfig: Map<String, String> = emptyMap(),
        repoDirConfig: Map<String, String> = emptyMap(),
        strings: List<String>,
        invokeLocation: File? = null,
        javaOptions: List<String> = emptyList(),
    ): String {
        val homeDir = scratchDir.homeDir()
        check(homeDir.exists() || homeDir.mkdir()) {
            "Failed to create homeDir ${homeDir.toStringWithClickableURI()}"
        }
        val defaults = mapOf(
            "github-token" to "REQUIRED_BY_CLI_BUT_UNUSED_IN_THESE_TESTS",
            "logs-directory" to scratchDir.logsDir().absolutePath,
        )
        homeDir.writeConfigFile(defaults + homeDirConfig)

        val repoDir = scratchDir.repoDir()

        repoDir.initGitDirWithRemoteUri(remoteUri, remoteName)
        repoDir.writeConfigFile(repoDirConfig)

        val command = getInvokeCliList(invokeLocation ?: repoDir, javaOptions) + strings + extraCliArgs
        logger.info("Executing command {}", command)
        val processResult = ProcessExecutor()
            .environment("HOME", homeDir.absolutePath)
            .command(command)
            .readOutput(true)
            .execute()

        val outputString = processResult.outputString()
        check(processResult.exitValue == 0) {
            "Process exit value was ${processResult.exitValue}, output was $outputString"
        }

        return outputString.orEmpty()
    }
}

private fun File.writeConfigFile(config: Map<String, String>) {
    require(config.keys.none { it.startsWith("--") }) {
        "Keys should not begin with `--`"
    }
    resolve(CONFIG_FILE_NAME).writer().use { writer ->
        Properties().apply { putAll(config) }.store(writer, null)
    }
}

private fun File.initGitDirWithRemoteUri(uriString: String, remoteName: String = "origin") {
    Git.init().setDirectory(this).call().use { git ->
        git.remoteAdd().setName(remoteName).setUri(URIish(uriString)).call()
    }
}

private fun getInvokeCliList(workingDir: File = findNearestGitDir(), javaOptions: List<String> = emptyList()): List<String> {
    // Use the same JDK we were invoked with, if we can determine it. Else fall back to whatever "java" is in our $PATH
    val javaBinary = System.getProperty("java.home")
        ?.let { javaHome -> "$javaHome/bin/java" }
        ?.takeIf { javaHomeBinary -> File(javaHomeBinary).exists() }
        ?: "java"
    return listOf(
        javaBinary,
        "-D${WORKING_DIR_PROPERTY_NAME}=${workingDir.absolutePath}",
        "-cp",
        System.getProperty("java.class.path"),
    ) + javaOptions + Cli::class.java.name
}

private fun findNearestGitDir() = File(".").findNearestGitDir()

private val json = Json {
    prettyPrint = true
}

private fun File.homeDir() = resolve("home")
private fun File.repoDir() = resolve("repo")
private fun File.logsDir() = resolve("logs")

data class ComparableConfig(val config: Config) {
    override fun toString(): String = json.encodeToString(config)
}
