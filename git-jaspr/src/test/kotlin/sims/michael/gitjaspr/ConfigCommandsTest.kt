package sims.michael.gitjaspr

import com.github.ajalt.clikt.core.BaseCliktCommand
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigCommandsTest {

    // region registry & validation

    @Test
    fun `known keys, unknown keys, and suggestions`() {
        assertTrue(isKnownConfigKey("remote-name"))
        assertTrue(isKnownConfigKey("forest.error.color"))
        assertFalse(isKnownConfigKey("remote-nam"))
        assertFalse(isKnownConfigKey("totally-made-up"))
        assertEquals("remote-name", suggestConfigKey("remote-nam"))
        assertNull(suggestConfigKey("zzzzzzzzzzzz"))
    }

    @Test
    fun `value validation`() {
        assertNull(validateConfigValue("show-tips", "true"))
        assertNotNullMessage(validateConfigValue("show-tips", "yes"))
        assertNull(validateConfigValue("log-level", "debug"))
        assertNotNullMessage(validateConfigValue("log-level", "LOUD"))
        assertNull(validateConfigValue("dont-push-regex", "^wip"))
        assertNotNullMessage(validateConfigValue("dont-push-regex", "("))
        assertNotNullMessage(validateConfigValue("remote-branch-prefix", "has/slash"))
    }

    @Test
    fun `theme key detection and validation`() {
        assertTrue(isThemeKey("ocean.heading.color"))
        assertTrue(isThemeKey("ocean.error.weight"))
        assertFalse(isThemeKey("ocean.notarole.color"))
        assertFalse(isThemeKey("ocean.heading.size"))
        assertNull(validateConfigValue("ocean.heading.color", "#74C0FC"))
        assertNull(validateConfigValue("ocean.heading.color", "74C0FC"))
        assertNotNullMessage(validateConfigValue("ocean.heading.color", "blue"))
        assertNull(validateConfigValue("ocean.error.weight", "bold"))
        assertNotNullMessage(validateConfigValue("ocean.error.weight", "heavy"))
    }

    // endregion

    // region file editor

    @Test
    fun `set creates the file when missing`() {
        withTempDir { dir ->
            val file = dir.resolve(CONFIG_FILE_NAME)
            ConfigFileEditor.setValue(file, "remote-name", "upstream")
            assertEquals("upstream", loadProperty(file, "remote-name"))
        }
    }

    @Test
    fun `set updates an existing key in place and preserves comments`() {
        withTempDir { dir ->
            val file = dir.resolve(CONFIG_FILE_NAME)
            file.writeText(
                """
                # a helpful comment
                remote-name=origin
                # trailing comment
                show-tips=true

                """
                    .trimIndent()
            )
            ConfigFileEditor.setValue(file, "remote-name", "upstream")
            val text = file.readText()
            assertTrue("# a helpful comment" in text) { "leading comment lost:\n$text" }
            assertTrue("# trailing comment" in text) { "trailing comment lost:\n$text" }
            assertTrue("remote-name=upstream" in text) { "value not updated:\n$text" }
            assertFalse("remote-name=origin" in text) { "old value not replaced:\n$text" }
            // Untouched keys survive.
            assertEquals("true", loadProperty(file, "show-tips"))
        }
    }

    @Test
    fun `set appends when the key is absent and keeps existing content`() {
        withTempDir { dir ->
            val file = dir.resolve(CONFIG_FILE_NAME)
            file.writeText("# header\ngithub-token=abc123\n")
            ConfigFileEditor.setValue(file, "remote-name", "upstream")
            assertEquals("abc123", loadProperty(file, "github-token"))
            assertEquals("upstream", loadProperty(file, "remote-name"))
            assertTrue("# header" in file.readText())
        }
    }

    @Test
    fun `set escapes backslashes so a regex value round-trips`() {
        withTempDir { dir ->
            val file = dir.resolve(CONFIG_FILE_NAME)
            val regex = "^(dont[ -]?push)\\b.*$"
            ConfigFileEditor.setValue(file, "dont-push-regex", regex)
            assertEquals(regex, loadProperty(file, "dont-push-regex"))
        }
    }

    @Test
    fun `unset removes a key while preserving comments and other keys`() {
        withTempDir { dir ->
            val file = dir.resolve(CONFIG_FILE_NAME)
            file.writeText("# header\nremote-name=origin\nshow-tips=false\n")
            assertTrue(ConfigFileEditor.unsetValue(file, "remote-name"))
            val text = file.readText()
            assertTrue("# header" in text)
            assertNull(loadProperty(file, "remote-name"))
            assertEquals("false", loadProperty(file, "show-tips"))
        }
    }

    @Test
    fun `unset returns false when the key is absent`() {
        withTempDir { dir ->
            val file = dir.resolve(CONFIG_FILE_NAME)
            file.writeText("show-tips=true\n")
            assertFalse(ConfigFileEditor.unsetValue(file, "remote-name"))
        }
    }

    // endregion

    // region resolver

    @Test
    fun `resolver prefers repo over home over default and reports the source`() {
        withTempDir { dir ->
            val home = dir.resolve("home.properties")
            val repo = dir.resolve("repo.properties")
            home.writeText("remote-name=home-remote\n")
            repo.writeText("remote-name=repo-remote\n")
            val resolver = ConfigResolver(home, repo)

            assertEquals(
                ResolvedConfigValue("repo-remote", ConfigSource.REPO_FILE),
                resolver.resolve("remote-name"),
            )
            ConfigFileEditor.unsetValue(repo, "remote-name")
            assertEquals(
                ResolvedConfigValue("home-remote", ConfigSource.HOME_FILE),
                resolver.resolve("remote-name"),
            )
            ConfigFileEditor.unsetValue(home, "remote-name")
            // Falls back to the registered default.
            assertEquals(
                ResolvedConfigValue(DEFAULT_REMOTE_NAME, ConfigSource.DEFAULT),
                resolver.resolve("remote-name"),
            )
        }
    }

    @Test
    fun `resolver reports unset for keys with no default`() {
        withTempDir { dir ->
            val home = dir.resolve("home.properties")
            val resolver = ConfigResolver(home, repoFile = null)
            assertEquals(
                ResolvedConfigValue(null, ConfigSource.UNSET),
                resolver.resolve("github-host"),
            )
        }
    }

    @Test
    fun `resolver surfaces theme keys present in the files`() {
        withTempDir { dir ->
            val home = dir.resolve("home.properties")
            home.writeText("theme=ocean\nocean.error.color=#FF0000\n")
            val resolver = ConfigResolver(home, repoFile = null)
            assertEquals(listOf("ocean.error.color"), resolver.presentThemeKeys())
        }
    }

    // endregion

    // region drift guard

    /**
     * Every registry key must be a real key that jaspr's Clikt value source reads, or `config set`
     * would silently write a key no command honors. This derives the true keys the same way the
     * value source does (see [collectValueSourceKeys]).
     */
    @Test
    fun `every config key is a real clikt value source key`() {
        val tempDir = Files.createTempDirectory("config-drift").toFile()
        JGitClient(tempDir).init()
        System.setProperty(WORKING_DIR_PROPERTY_NAME, tempDir.absolutePath)
        try {
            val validKeys = collectValueSourceKeys(buildCommand())
            val missing = CONFIG_KEYS.map(ConfigKeySpec::key).filterNot(validKeys::contains)
            assertTrue(missing.isEmpty()) {
                "These registry keys are not real Clikt value-source keys: $missing\n" +
                    "Known keys: ${validKeys.sorted()}"
            }
        } finally {
            System.clearProperty(WORKING_DIR_PROPERTY_NAME)
            tempDir.deleteRecursively()
        }
    }

    private fun collectValueSourceKeys(command: BaseCliktCommand<*>): Set<String> = buildSet {
        for (option in command.registeredOptions()) {
            val key =
                option.valueSourceKey
                    ?: option.names.maxByOrNull(String::length)?.removePrefix("--")
                    ?: continue
            add(key)
        }
        for (sub in command.registeredSubcommands()) {
            addAll(collectValueSourceKeys(sub))
        }
    }

    // endregion

    private fun assertNotNullMessage(message: String?) =
        assertTrue(message != null) { "expected a validation error message but got null" }

    private fun loadProperty(file: File, key: String): String? =
        Properties().apply { file.reader().use(::load) }.getProperty(key)

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory(ConfigCommandsTest::class.java.simpleName).toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
