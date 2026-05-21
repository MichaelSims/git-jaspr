package sims.michael.gitjaspr

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PullPlanTest {

    // region punt cases

    @Test
    fun `punts when any shared commit is DIVERGED`() {
        val local = listOf(c("a"), c("b"))
        val remote = listOf(c("a"), c("b", hash = "bbb-remote"))
        assertEquals(
            PullPlan.Punt(PuntReason.DIVERGED),
            getPullPlan(local, remote, remoteTip(remote), BaseRelation.EQUAL, setOf("b")),
        )
    }

    @Test
    fun `punts when both sides have unique commits`() {
        val local = listOf(c("a"), c("b"), c("c"))
        val remote = listOf(c("a"), c("b"), c("d"))
        assertEquals(
            PullPlan.Punt(PuntReason.MIXED_UNIQUE_WORK),
            getPullPlan(local, remote, remoteTip(remote), BaseRelation.EQUAL, emptySet()),
        )
    }

    @Test
    fun `punts when bases are unrelated`() {
        val local = listOf(c("a"))
        val remote = listOf(c("a"))
        assertEquals(
            PullPlan.Punt(PuntReason.UNRELATED_BASES),
            getPullPlan(local, remote, remoteTip(remote), BaseRelation.UNRELATED, emptySet()),
        )
    }

    // endregion

    // region no-op cases

    @Test
    fun `no-op when literally up to date`() {
        val local = listOf(c("a"), c("b"))
        val remote = listOf(c("a"), c("b"))
        assertEquals(
            PullPlan.NoOp(NoOpReason.UP_TO_DATE),
            getPullPlan(local, remote, remoteTip(remote), BaseRelation.EQUAL, emptySet()),
        )
    }

    @Test
    fun `no-op with pure reordering note when commit-id sets match but order differs`() {
        val local = listOf(c("a"), c("c"), c("b"))
        val remote = listOf(c("a"), c("b"), c("c"))
        assertEquals(
            PullPlan.NoOp(NoOpReason.PURE_REORDERING),
            getPullPlan(local, remote, remoteTip(remote), BaseRelation.EQUAL, emptySet()),
        )
    }

    @Test
    fun `no-op LOCAL_AHEAD when local base is ahead and there are no remote-only commits`() {
        val local = listOf(c("a"), c("b"))
        val remote = listOf(c("a"), c("b"))
        assertEquals(
            PullPlan.NoOp(NoOpReason.LOCAL_AHEAD),
            getPullPlan(local, remote, remoteTip(remote), BaseRelation.LOCAL_AHEAD, emptySet()),
        )
    }

    @Test
    fun `no-op LOCAL_HAS_UNPUSHED when local has extras and remote is unchanged`() {
        val local = listOf(c("a"), c("b"), c("c"))
        val remote = listOf(c("a"), c("b"))
        assertEquals(
            PullPlan.NoOp(NoOpReason.LOCAL_HAS_UNPUSHED),
            getPullPlan(local, remote, remoteTip(remote), BaseRelation.EQUAL, emptySet()),
        )
    }

    // endregion

    // region reset --hard cases

    @Test
    fun `reset --hard when LO empty and remote has new commits on top`() {
        val local = listOf(c("a"), c("b"))
        val remote = listOf(c("a"), c("b"), c("c"))
        val expected = PullPlan.HardResetToRemoteTip(remoteTip(remote))
        assertEquals(
            expected,
            getPullPlan(local, remote, remoteTip(remote), BaseRelation.EQUAL, emptySet()),
        )
    }

    @Test
    fun `reset --hard when LO empty and remote rebased shared commits in place`() {
        val local = listOf(c("a"), c("b", hash = "bbb-local"))
        val remote = listOf(c("a"), c("b", hash = "bbb-remote"))
        assertEquals(
            PullPlan.HardResetToRemoteTip(remoteTip(remote)),
            getPullPlan(local, remote, remoteTip(remote), BaseRelation.EQUAL, emptySet()),
        )
    }

    @Test
    fun `reset --hard when LO empty and remote base is ahead`() {
        val local = listOf(c("a"), c("b"))
        val remote = listOf(c("a", hash = "aaa-r"), c("b", hash = "bbb-r"), c("c"))
        assertEquals(
            PullPlan.HardResetToRemoteTip(remoteTip(remote)),
            getPullPlan(local, remote, remoteTip(remote), BaseRelation.REMOTE_AHEAD, emptySet()),
        )
    }

    // endregion

    // region cherry-pick LO onto remote tip

    @Test
    fun `cherry-pick LO onto remote tip when local has extras and remote rebased in place`() {
        val cLocal = c("c")
        val local = listOf(c("a"), c("b", hash = "bbb-local"), cLocal)
        val remote = listOf(c("a"), c("b", hash = "bbb-remote"))
        val plan = getPullPlan(local, remote, remoteTip(remote), BaseRelation.EQUAL, emptySet())
        assertEquals(PullPlan.CherryPickLoOntoRemoteTip(listOf(cLocal), remoteTip(remote)), plan)
    }

    @Test
    fun `cherry-pick LO onto remote tip when local has extras and remote base is ahead`() {
        val cLocal = c("c")
        val local = listOf(c("a"), c("b"), cLocal)
        val remote = listOf(c("a", hash = "aaa-r"), c("b", hash = "bbb-r"))
        val plan =
            getPullPlan(local, remote, remoteTip(remote), BaseRelation.REMOTE_AHEAD, emptySet())
        assertEquals(PullPlan.CherryPickLoOntoRemoteTip(listOf(cLocal), remoteTip(remote)), plan)
    }

    // endregion

    // region cherry-pick RO onto local HEAD

    @Test
    fun `cherry-pick RO onto local HEAD when LO empty and local base is ahead`() {
        val cRemote = c("c")
        val local = listOf(c("a", hash = "aaa-l"), c("b", hash = "bbb-l"))
        val remote = listOf(c("a"), c("b"), cRemote)
        val plan =
            getPullPlan(local, remote, remoteTip(remote), BaseRelation.LOCAL_AHEAD, emptySet())
        assertEquals(PullPlan.CherryPickRoOntoLocalHead(listOf(cRemote)), plan)
    }

    // endregion

    // region helpers

    private fun c(id: String, hash: String = "sha-$id"): Commit {
        val epoch = ZonedDateTime.ofInstant(java.time.Instant.EPOCH, ZoneOffset.UTC)
        return Commit(
            hash = hash,
            shortMessage = id,
            fullMessage = id,
            id = id,
            author = Ident("Test", "test@example.com"),
            committer = Ident("Test", "test@example.com"),
            commitDate = epoch,
            authorDate = epoch,
        )
    }

    private fun remoteTip(remote: List<Commit>): String = remote.last().hash

    // endregion
}
