package sims.michael.gitjaspr

import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CommitCompareTest {

    private val older = ZonedDateTime.parse("2026-01-01T00:00:00Z")
    private val newer = older.plusMinutes(5)

    @Test
    fun `integer within row range resolves as an index without resolving a ref`() {
        val rows = listOf(row(1, CompareRelation.IDENTICAL), row(2, CompareRelation.IDENTICAL))
        val index = resolveCompareIndex(rows, "2") { error("should not resolve '$it'") }
        assertEquals(2, index)
    }

    @Test
    fun `integer outside row range resolves as a ref`() {
        val rows = listOf(row(1, CompareRelation.IDENTICAL, localHash = "1234567abc"))
        val index = resolveCompareIndex(rows, "1234567") { "1234567abc" }
        assertEquals(1, index)
    }

    @Test
    fun `ref matching the remote side resolves to that row`() {
        val rows =
            listOf(
                row(1, CompareRelation.IDENTICAL),
                row(2, CompareRelation.DIVERGED_LOCAL_NEWER, remoteHash = "remote-two"),
            )
        assertEquals(2, resolveCompareIndex(rows, "some-ref") { "remote-two" })
    }

    @Test
    fun `unresolvable selector fails with a user-facing message`() {
        val rows = listOf(row(1, CompareRelation.IDENTICAL))
        val thrown =
            assertFailsWith<GitJasprException> { resolveCompareIndex(rows, "nope") { null } }
        assertTrue(thrown.message.contains("neither a row index"), thrown.message)
    }

    @Test
    fun `commit outside both stacks fails with a user-facing message`() {
        val rows = listOf(row(1, CompareRelation.IDENTICAL))
        val thrown =
            assertFailsWith<GitJasprException> {
                resolveCompareIndex(rows, "main") { "unrelated-sha" }
            }
        assertTrue(thrown.message.contains("isn't in the local stack"), thrown.message)
    }

    @Test
    fun `local newer puts remote on the left and local on the right`() {
        val rows = listOf(row(1, CompareRelation.DIVERGED_LOCAL_NEWER))
        val comparison = assertIs<CommitComparison.Diverged>(buildCommitComparison(rows, 1))
        assertEquals(StackSide.REMOTE, comparison.left.side)
        assertEquals(StackSide.LOCAL, comparison.right.side)
        assertEquals(StackSide.LOCAL, comparison.newerSide)
    }

    @Test
    fun `remote newer puts local on the left and remote on the right`() {
        val rows = listOf(row(1, CompareRelation.DIVERGED_REMOTE_NEWER))
        val comparison = assertIs<CommitComparison.Diverged>(buildCommitComparison(rows, 1))
        assertEquals(StackSide.LOCAL, comparison.left.side)
        assertEquals(StackSide.REMOTE, comparison.right.side)
    }

    @Test
    fun `equal dates fall back to remote on the left`() {
        val rows = listOf(row(1, CompareRelation.DIVERGED_EQUAL_DATE))
        val comparison = assertIs<CommitComparison.Diverged>(buildCommitComparison(rows, 1))
        assertEquals(StackSide.REMOTE, comparison.left.side)
        assertNull(comparison.newerSide)
    }

    @Test
    fun `reverse swaps the sides`() {
        val rows = listOf(row(1, CompareRelation.DIVERGED_LOCAL_NEWER))
        val comparison =
            assertIs<CommitComparison.Diverged>(buildCommitComparison(rows, 1, reverse = true))
        assertEquals(StackSide.LOCAL, comparison.left.side)
        assertEquals(StackSide.REMOTE, comparison.right.side)
    }

    @Test
    fun `reordered commit pairs its two one-sided rows by shared index`() {
        val localCommit = commit("local-two", "two", newer)
        val remoteCommit = commit("remote-two", "two", older)
        val rows =
            listOf(
                CompareRow(localCommit, null, CompareRelation.DIVERGED_LOCAL_NEWER, 1),
                row(2, CompareRelation.IDENTICAL),
                CompareRow(null, remoteCommit, CompareRelation.DIVERGED_LOCAL_NEWER, 1),
            )
        val comparison = assertIs<CommitComparison.Diverged>(buildCommitComparison(rows, 1))
        assertEquals(remoteCommit, comparison.left.commit)
        assertEquals(localCommit, comparison.right.commit)
    }

    @Test
    fun `one-sided row has nothing to compare against`() {
        val rows = listOf(CompareRow(commit("a", "a", older), null, CompareRelation.LOCAL_ONLY, 1))
        val comparison = assertIs<CommitComparison.OneSided>(buildCommitComparison(rows, 1))
        assertEquals(StackSide.LOCAL, comparison.side)
    }

    @Test
    fun `identical row builds an identical comparison`() {
        val rows = listOf(row(1, CompareRelation.IDENTICAL))
        assertIs<CommitComparison.Identical>(buildCommitComparison(rows, 1))
    }

    private fun row(
        index: Int,
        relation: CompareRelation,
        localHash: String = "local-$index",
        remoteHash: String = "remote-$index",
    ): CompareRow {
        val (localDate, remoteDate) =
            when (relation) {
                CompareRelation.DIVERGED_REMOTE_NEWER -> older to newer
                CompareRelation.DIVERGED_EQUAL_DATE -> older to older
                else -> newer to older
            }
        return CompareRow(
            local = commit(localHash, "id-$index", localDate),
            remote = commit(remoteHash, "id-$index", remoteDate),
            relation = relation,
            index = index,
        )
    }

    private fun commit(hash: String, id: String, date: ZonedDateTime): Commit {
        val ident = Ident("Test", "test@example.com")
        return Commit(hash, "subject $id", "subject $id", id, ident, ident, date, date)
    }
}
