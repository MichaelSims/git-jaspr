package sims.michael.gitjaspr

import java.time.ZonedDateTime
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class CompareRenderTest {

    @Test
    fun `columns stay aligned when indexes reach two digits`() {
        val rows =
            (1..10).map { index ->
                CompareRow(
                    local = commit("a%06d".format(index)),
                    remote = commit("b%06d".format(index)),
                    relation = CompareRelation.IDENTICAL,
                    index = index,
                )
            }

        val lines =
            renderCompare(rows, "origin/stack", MonoTheme)
                .lines()
                .drop(1)
                .filter(String::isNotEmpty)

        for (prefix in listOf("a", "b")) {
            val columns = lines.map { line -> Regex("$prefix\\d{6}").find(line)?.range?.first }
            assertEquals(
                1,
                columns.toSet().size,
                "Expected '$prefix' SHAs in one column:\n${lines.joinToString("\n")}",
            )
        }
    }

    private fun commit(hash: String): Commit {
        val ident = Ident("Test", "test@example.com")
        val date = ZonedDateTime.parse("2026-01-01T00:00:00Z")
        return Commit(hash, "subject", "subject", hash, ident, ident, date, date)
    }
}
