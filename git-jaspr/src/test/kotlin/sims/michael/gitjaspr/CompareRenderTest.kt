package sims.michael.gitjaspr

import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CompareRenderTest {

    @Test
    fun `columns stay aligned when indexes reach two digits`() {
        val rows = (1..10).map(::identicalRow)

        val lines = renderedRowLines(rows, DEFAULT_MAX_SUBJECT_LENGTH)

        for (prefix in listOf("a", "b")) {
            val columns = lines.map { line -> Regex("$prefix\\d{6}").find(line)?.range?.first }
            assertEquals(
                1,
                columns.toSet().size,
                "Expected '$prefix' SHAs in one column:\n${lines.joinToString("\n")}",
            )
        }
    }

    @Test
    fun `overview ends with a summary of the rows`() {
        val rows = listOf(identicalRow(1), identicalRow(2))
        val output = renderCompare(rows, "origin/stack", MonoTheme)
        assertEquals("2 identical, 0 diverged", output.trimEnd().lines().last())
    }

    @Test
    fun `summary counts each kind and lists a reordered commit once`() {
        val rows =
            listOf(
                CompareRow(null, commit("r000001"), CompareRelation.REMOTE_ONLY, 1),
                CompareRow(commit("a000002"), null, CompareRelation.DIVERGED_LOCAL_NEWER, 2),
                identicalRow(3),
                CompareRow(null, commit("b000002"), CompareRelation.DIVERGED_LOCAL_NEWER, 2),
                CompareRow(commit("l000004"), null, CompareRelation.LOCAL_ONLY, 4),
                CompareRow(
                    commit("a000005"),
                    commit("b000005"),
                    CompareRelation.DIVERGED_REMOTE_NEWER,
                    5,
                ),
            )
        assertEquals(
            "1 identical, 2 diverged, 1 local-only, 1 remote-only, 1 reordered",
            summarizeCompare(rows),
        )
    }

    @Test
    fun `fitted subject length keeps full subjects on a wide terminal`() {
        val rows = listOf(identicalRow(1, subject = "x".repeat(70)))
        assertEquals(70, fitSubjectLength(rows, width = 400))
    }

    @Test
    fun `fitted subject length keeps every row within the terminal width`() {
        val rows = (1..12).map { index -> identicalRow(index, subject = "s".repeat(70)) }
        val width = 120
        val subjectLength = fitSubjectLength(rows, width)
        val fitted = renderedRowLines(rows, subjectLength)
        assertTrue(subjectLength < 70, "Expected truncation at width $width")
        assertTrue(fitted.all { line -> line.length <= width }, fitted.joinToString("\n"))
        val oneMore = renderedRowLines(rows, subjectLength + 1)
        assertTrue(oneMore.any { line -> line.length > width }, "Expected the longest fit")
    }

    @Test
    fun `fitted subject length has a floor on very narrow terminals`() {
        val rows = listOf(identicalRow(1, subject = "x".repeat(70)))
        assertEquals(MIN_FITTED_SUBJECT_LENGTH, fitSubjectLength(rows, width = 10))
    }

    private fun renderedRowLines(rows: List<CompareRow>, maxSubjectLength: Int) =
        renderCompare(rows, "origin/stack", MonoTheme, maxSubjectLength).lines().filter { line ->
            line.startsWith("[")
        }

    private fun identicalRow(index: Int, subject: String = "subject") =
        CompareRow(
            local = commit("a%06d".format(index), subject),
            remote = commit("b%06d".format(index), subject),
            relation = CompareRelation.IDENTICAL,
            index = index,
        )

    private fun commit(hash: String, subject: String = "subject"): Commit {
        val ident = Ident("Test", "test@example.com")
        val date = ZonedDateTime.parse("2026-01-01T00:00:00Z")
        return Commit(hash, subject, subject, hash, ident, ident, date, date)
    }
}
