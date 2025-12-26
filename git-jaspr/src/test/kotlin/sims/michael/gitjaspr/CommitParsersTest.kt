package sims.michael.gitjaspr

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import sims.michael.gitjaspr.CommitParsers.SubjectAndBody
import sims.michael.gitjaspr.CommitParsers.addFooters
import sims.michael.gitjaspr.CommitParsers.getFooters
import sims.michael.gitjaspr.CommitParsers.getSubjectAndBodyFromFullMessage
import sims.michael.gitjaspr.CommitParsers.trimFooters

class CommitParsersTest {

    @Test
    fun `getSubjectAndBodyFromFullMessage - subject only`() {
        assertEquals(
            SubjectAndBody("This is a subject", null),
            getSubjectAndBodyFromFullMessage("This is a subject"),
        )
    }

    @Test
    fun `getSubjectAndBodyFromFullMessage - subject with newline`() {
        assertEquals(
            SubjectAndBody("This is a subject", null),
            getSubjectAndBodyFromFullMessage("This is a subject\n"),
        )
    }

    @Test
    fun `getSubjectAndBodyFromFullMessage - subject and body`() {
        val message =
            """
                This is a subject
                
                This is a body
                
            """
                .trimIndent()

        assertEquals(
            SubjectAndBody("This is a subject", "This is a body"),
            getSubjectAndBodyFromFullMessage(message),
        )
    }

    @Test
    fun `getSubjectAndBodyFromFullMessage - multiline subject`() {
        val message =
            """
                This is a subject
                with three lines
                but still a subject
                
                This is a body
                
            """
                .trimIndent()

        assertEquals(
            SubjectAndBody(
                "This is a subject with three lines but still a subject",
                "This is a body",
            ),
            getSubjectAndBodyFromFullMessage(message),
        )
    }

    @Test
    fun `getFooters - subject only`() {
        assertEquals(emptyMap(), getFooters("This is a subject"))
    }

    @Test
    fun `getFooters - subject with newline`() {
        assertEquals(emptyMap(), getFooters("This is a subject\n"))
    }

    @Test
    fun `getFooters - subject and body only`() {
        val message =
            """
                This is a subject

                This is a body

            """
                .trimIndent()

        assertEquals(emptyMap(), getFooters(message))
    }

    @Test
    fun `getFooters - subject, body with footer-like lines`() {
        val message =
            """
                This is a subject

                This is a body.
                The following are still part of the body:
                key-one: value-one
                key-two: value-two

            """
                .trimIndent()

        assertEquals(emptyMap(), getFooters(message))
    }

    @Test
    fun `getFooters - subject, body url that could look like a footer line if your code was bad`() {
        val message =
            """
                This is a subject

                See this Slack thread:
                https://trillianthealth.slack.com/archives/C04J6Q655GR/p1702918943374039?thread_ts=1702918322.439999&cid=C04J6Q655GR

            """
                .trimIndent()

        assertEquals(emptyMap(), getFooters(message))
    }

    @Test
    fun `getFooters - subject, body, existing footer lines`() {
        val message =
            """
                This is a subject

                This is a body.
                The following are still part of the body:
                key-one: value-one
                key-two: value-two
                
                key-one: value-three
                key-two: value-four

            """
                .trimIndent()

        assertEquals(
            mapOf("key-one" to "value-three", "key-two" to "value-four"),
            getFooters(message),
        )
    }

    @Test
    fun `getFooters - subject, body, existing footer lines with multiples - last one wins`() {
        val message =
            """
                This is a subject

                This is a body.
                The following are still part of the body:
                key-one: value-one
                key-two: value-two
                
                key-one: value-three
                key-one: value-four

            """
                .trimIndent()

        assertEquals(mapOf("key-one" to "value-four"), getFooters(message))
    }

    @Test
    fun `getFooters - footer value with spaces`() {
        val message =
            """
            This is a commit subject

            Co-authored-by: John Carmack <jcarmack@idsoftware.com>
            commit-id: I0e9e0b26
                    """
                .trimIndent()

        assertEquals(
            mapOf(
                "Co-authored-by" to "John Carmack <jcarmack@idsoftware.com>",
                "commit-id" to "I0e9e0b26",
            ),
            getFooters(message),
        )
    }

    @Test
    fun `getFooters - footer key with spaces`() {
        val message =
            """
            This is a commit subject

            keys with spaces are not allowed: value
                    """
                .trimIndent()

        assertEquals(emptyMap(), getFooters(message))
    }

    @Test
    fun `addFooters - subject only`() {
        assertEquals(
            """
                This is a subject
                
                key1: value1

            """
                .trimIndent(),
            addFooters("This is a subject", mapOf("key1" to "value1")),
        )
    }

    @Test
    fun `addFooters - subject with newline`() {
        assertEquals(
            """
                This is a subject
                
                key1: value1

            """
                .trimIndent(),
            addFooters("This is a subject\n", mapOf("key1" to "value1")),
        )
    }

    @Test
    fun `addFooters - subject and body only`() {
        val message =
            """
                This is a subject

                This is a body

            """
                .trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body
            
            key1: value1

            """
                .trimIndent(),
            addFooters(message, mapOf("key1" to "value1")),
        )
    }

    @Test
    fun `addFooters - subject, body with footer-like lines`() {
        val message =
            """
                This is a subject

                This is a body.
                The following are still part of the body:
                key-one: value-one
                key-two: value-two

            """
                .trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two
            
            key1: value1

            """
                .trimIndent(),
            addFooters(message, mapOf("key1" to "value1")),
        )
    }

    @Test
    fun `addFooters - subject, body, existing footer lines`() {
        val message =
            """
                This is a subject

                This is a body.
                The following are still part of the body:
                key-one: value-one
                key-two: value-two
                
                key-one: value-three
                key-two: value-four

            """
                .trimIndent()

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

            """
                .trimIndent(),
            addFooters(message, mapOf("key1" to "value1")),
        )
    }

    @Test
    fun `addFooters - subject, body, existing footer lines with multiples - last one wins`() {
        val message =
            """
                This is a subject

                This is a body.
                The following are still part of the body:
                key-one: value-one
                key-two: value-two
                
                key-one: value-three
                key-one: value-four

            """
                .trimIndent()

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

            """
                .trimIndent(),
            addFooters(message, mapOf("key1" to "value1")),
        )
    }

    @Test
    fun `addFooters - subject that looks like a footer line`() {
        val message =
            """
                Market Explorer: Remove unused code

            """
                .trimIndent()

        assertEquals(
            """
            Market Explorer: Remove unused code

            key1: value1

            """
                .trimIndent(),
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

                """
                    .trimIndent()
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

                """
                    .trimIndent()
            ),
        )
    }

    @Test
    fun `trimFooters - subject and body only`() {
        val message =
            """
                This is a subject

                This is a body

            """
                .trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body

            """
                .trimIndent(),
            trimFooters(message),
        )
    }

    @Test
    fun `trimFooters - subject, body with footer-like lines`() {
        val message =
            """
                This is a subject

                This is a body.
                The following are still part of the body:
                key-one: value-one
                key-two: value-two

            """
                .trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two

            """
                .trimIndent(),
            trimFooters(message),
        )
    }

    @Test
    fun `trimFooters - subject, body, existing footer lines`() {
        val message =
            """
                This is a subject

                This is a body.
                The following are still part of the body:
                key-one: value-one
                key-two: value-two
                
                key-one: value-three
                key-two: value-four

            """
                .trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two

            """
                .trimIndent(),
            trimFooters(message),
        )
    }

    @Test
    fun `trimFooters - subject, body, existing footer lines with multiples - last one wins`() {
        val message =
            """
                This is a subject

                This is a body.
                The following are still part of the body:
                key-one: value-one
                key-two: value-two
                
                key-one: value-three
                key-one: value-four

            """
                .trimIndent()

        assertEquals(
            """
            This is a subject

            This is a body.
            The following are still part of the body:
            key-one: value-one
            key-two: value-two

            """
                .trimIndent(),
            trimFooters(message),
        )
    }
}
