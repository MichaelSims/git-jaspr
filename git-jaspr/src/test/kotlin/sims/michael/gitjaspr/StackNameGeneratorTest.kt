package sims.michael.gitjaspr

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class StackNameGeneratorTest {

    private val logger = LoggerFactory.getLogger(StackNameGeneratorTest::class.java)

    @Test
    fun `basic subject is slugified`() {
        assertEquals(
            "fix-null-pointer-in-parser",
            StackNameGenerator.generateName("Fix null pointer in parser"),
        )
    }

    @Test
    fun `special characters are replaced with hyphens`() {
        assertEquals("add-foo-bar-support", StackNameGenerator.generateName("Add foo/bar support!"))
    }

    @Test
    fun `consecutive special characters collapse to single hyphen`() {
        assertEquals("a-b", StackNameGenerator.generateName("a --- b"))
    }

    @Test
    fun `leading and trailing special characters are trimmed`() {
        assertEquals("hello-world", StackNameGenerator.generateName("--hello world--"))
    }

    @Test
    fun `long subjects are truncated at word boundary`() {
        val longSubject =
            "refactor the extremely long-winded module name that exceeds the maximum allowed length for branch names"
        val result = StackNameGenerator.generateName(longSubject)
        logger.info("Generated stack name: {}", result)
        assertTrue(result.length <= 60)
        // Should not end with a hyphen
        assertTrue(!result.endsWith("-"))
    }

    @Test
    fun `empty subject returns fallback`() {
        assertEquals("stack", StackNameGenerator.generateName(""))
    }

    @Test
    fun `blank subject returns fallback`() {
        assertEquals("stack", StackNameGenerator.generateName("   "))
    }

    @Test
    fun `subject with only special characters returns fallback`() {
        assertEquals("stack", StackNameGenerator.generateName("!@#$%^&*()"))
    }

    @Test
    fun `generateSuffix returns 4 lowercase letters`() {
        val suffix = StackNameGenerator.generateSuffix(Random(42))
        logger.info("Generated suffix: {}", suffix)
        assertEquals(4, suffix.length)
        assertTrue(suffix.all { it in 'a'..'z' })
    }

    @Test
    fun `generateSuffix with same seed produces same result`() {
        val suffix1 = StackNameGenerator.generateSuffix(Random(123))
        val suffix2 = StackNameGenerator.generateSuffix(Random(123))
        assertEquals(suffix1, suffix2)
    }
}
