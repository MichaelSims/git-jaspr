package sims.michael.gitjaspr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StackNameGeneratorTest {

    // StackNameGenerator was AI-generated, so I want this test suite in case we add to it later.
    @Test
    fun `all adjectives are unique`() {
        val adjectives = StackNameGenerator.adjectives
        val uniqueAdjectives = adjectives.toSet()

        assertEquals(
            adjectives.size,
            uniqueAdjectives.size,
            "Found ${adjectives.size - uniqueAdjectives.size} duplicate adjectives. " +
                "Duplicates: ${adjectives.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}",
        )
    }

    @Test
    fun `all nouns are unique`() {
        val nouns = StackNameGenerator.nouns
        val uniqueNouns = nouns.toSet()

        assertEquals(
            nouns.size,
            uniqueNouns.size,
            "Found ${nouns.size - uniqueNouns.size} duplicate nouns. " +
                "Duplicates: ${nouns.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}",
        )
    }
}
