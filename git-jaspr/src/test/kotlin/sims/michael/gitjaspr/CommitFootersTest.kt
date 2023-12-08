package sims.michael.gitjaspr

import org.junit.jupiter.api.Test
import sims.michael.gitjaspr.CommitFooters.addFooters
import sims.michael.gitjaspr.CommitFooters.getFooters
import sims.michael.gitjaspr.CommitFooters.trimFooters
import kotlin.test.assertEquals

class CommitFootersTest {

    @Test
    fun `getFooters - subject only`() {
        assertEquals(
            emptyMap(),
            getFooters("This is a subject"),
        )
    }

    @Test
    fun `getFooters - subject with newline`() {
        assertEquals(
            emptyMap(),
            getFooters("This is a subject\n"),
        )
    }

    @Test
    fun `getFooters - subject and body only`() {
        val message = """
            This is a subject

            This is a body

        """.trimIndent()

        assertEquals(emptyMap(), getFooters(message))
    }

    @Test
    fun `getFooters - subject, body with footer-like lines`() {
        val message = """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two

        """.trimIndent()

        assertEquals(emptyMap(), getFooters(message))
    }

    @Test
    fun `getFooters - subject, body, existing footer lines`() {
        val message = """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two
            
            key-one: value-three
            key-two: value-four

        """.trimIndent()

        assertEquals(mapOf("key-one" to "value-three", "key-two" to "value-four"), getFooters(message))
    }

    @Test
    fun `getFooters - subject, body, existing footer lines with multiples - last one wins`() {
        val message = """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two
            
            key-one: value-three
            key-one: value-four

        """.trimIndent()

        assertEquals(mapOf("key-one" to "value-four"), getFooters(message))
    }

    @Test
    fun `addFooters - subject only`() {
        assertEquals(
            """
                This is a subject
                
                key1: value1

            """.trimIndent(),
            addFooters("This is a subject", mapOf("key1" to "value1")),
        )
    }

    @Test
    fun `addFooters - subject with newline`() {
        assertEquals(
            """
                This is a subject
                
                key1: value1

            """.trimIndent(),
            addFooters("This is a subject\n", mapOf("key1" to "value1")),
        )
    }

    @Test
    fun `addFooters - subject and body only`() {
        val message = """
            This is a subject

            This is a body

        """.trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body
            
            key1: value1

            """.trimIndent(),
            addFooters(message, mapOf("key1" to "value1")),
        )
    }

    @Test
    fun `addFooters - subject, body with footer-like lines`() {
        val message = """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two

        """.trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two
            
            key1: value1

            """.trimIndent(),
            addFooters(message, mapOf("key1" to "value1")),
        )
    }

    @Test
    fun `addFooters - subject, body, existing footer lines`() {
        val message = """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two
            
            key-one: value-three
            key-two: value-four

        """.trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two
            
            key-one: value-three
            key-two: value-four
            key1: value1

            """.trimIndent(),
            addFooters(message, mapOf("key1" to "value1")),
        )
    }

    @Test
    fun `addFooters - subject, body, existing footer lines with multiples - last one wins`() {
        val message = """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two
            
            key-one: value-three
            key-one: value-four

        """.trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two

            key-one: value-three
            key-one: value-four
            key1: value1

            """.trimIndent(),
            addFooters(message, mapOf("key1" to "value1")),
        )
    }

    @Test
    fun `trimFooters - subject only`() {
        assertEquals(
            "This is a subject\n",
            trimFooters(
                """
                This is a subject
                
                key1: value1

                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `trimFooters - subject with newline`() {
        assertEquals(
            "This is a subject\n",
            trimFooters(
                """
                This is a subject
                
                key1: value1

                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `trimFooters - subject and body only`() {
        val message = """
            This is a subject

            This is a body

        """.trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body

            """.trimIndent(),
            trimFooters(message),
        )
    }

    @Test
    fun `trimFooters - subject, body with footer-like lines`() {
        val message = """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two

        """.trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two

            """.trimIndent(),
            trimFooters(message),
        )
    }

    @Test
    fun `trimFooters - subject, body, existing footer lines`() {
        val message = """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two
            
            key-one: value-three
            key-two: value-four

        """.trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two

            """.trimIndent(),
            trimFooters(message),
        )
    }

    @Test
    fun `trimFooters - subject, body, existing footer lines with multiples - last one wins`() {
        val message = """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two
            
            key-one: value-three
            key-one: value-four

        """.trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two

            """.trimIndent(),
            trimFooters(message),
        )
    }
}
