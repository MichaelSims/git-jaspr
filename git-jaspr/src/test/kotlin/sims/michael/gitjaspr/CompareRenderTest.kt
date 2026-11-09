package sims.michael.gitjaspr

import java.time.ZonedDateTime
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class CompareRenderTest {

    @Test
    fun `columns stay aligned when indexes reach two digits`() {
        val rows = (1..10).map(::identicalRow)

        val lines =
            renderCompare(rows, "origin/stack", MonoTheme).lines().filter { line ->
                line.startsWith("[")
            }

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

    private fun identicalRow(index: Int) =
        CompareRow(
            local = commit("a%06d".format(index)),
            remote = commit("b%06d".format(index)),
            relation = CompareRelation.IDENTICAL,
            index = index,
        )

    private fun commit(hash: String): Commit {
        val ident = Ident("Test", "test@example.com")
        val date = ZonedDateTime.parse("2026-01-01T00:00:00Z")
        return Commit(hash, "subject", "subject", hash, ident, ident, date, date)
    }
}
