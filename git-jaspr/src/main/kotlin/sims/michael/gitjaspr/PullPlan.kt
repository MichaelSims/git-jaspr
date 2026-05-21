package sims.michael.gitjaspr

/**
 * The action that [getPullPlan] selects for a given pair of stacks. Pure data; the executor maps
 * each variant to git operations.
 *
 * See `doc/adr/0003-pull-command-scope.md` for the decision tree these variants come from.
 */
sealed interface PullPlan {
    /** Pull found nothing actionable. [reason] explains which subcase applies. */
    data class NoOp(val reason: NoOpReason) : PullPlan

    /**
     * Adopt the remote stack wholesale by moving the local stack ref to [remoteTipSha] (via `git
     * reset --hard`). Applies when local has nothing remote doesn't already have and remote has
     * fresh state.
     */
    data class HardResetToRemoteTip(val remoteTipSha: String) : PullPlan

    /**
     * Adopt remote's view of the shared portion and replay local-only commits on top. The
     * cherry-pick happens against [remoteTipSha]; [commits] are the LO commits in local order.
     */
    data class CherryPickLoOntoRemoteTip(val commits: List<Commit>, val remoteTipSha: String) :
        PullPlan

    /**
     * Preserve local's view (including a local rebase onto a newer base) and append remote-only
     * commits. The cherry-pick happens against local HEAD; [commits] are the RO commits in remote
     * order.
     */
    data class CherryPickRoOntoLocalHead(val commits: List<Commit>) : PullPlan

    /** Pull refused to act. [reason] explains which condition tripped. */
    data class Punt(val reason: PuntReason) : PullPlan
}

/** Distinguishes the three no-op sub-cases plus the pure-reordering note. */
enum class NoOpReason {
    /** Local and remote match bit-for-bit. */
    UP_TO_DATE,
    /** Local rebased onto a newer base; remote has nothing to contribute. */
    LOCAL_AHEAD,
    /** Local has unpushed commits, remote is unchanged. */
    LOCAL_HAS_UNPUSHED,
    /** Same commit-id sets on both sides but in different orders. */
    PURE_REORDERING,
}

enum class PuntReason {
    /** At least one shared commit-id classifies as DIVERGED (different content or message). */
    DIVERGED,
    /** Both sides have commit-ids the other doesn't. */
    MIXED_UNIQUE_WORK,
    /** Stack bases are unrelated; neither is an ancestor of the other. */
    UNRELATED_BASES,
}

/** Relation between the local stack base and the remote stack base in the target ref's history. */
enum class BaseRelation {
    /** Both stacks are rooted at the same commit. */
    EQUAL,
    /** Local base is strictly ahead of remote base (local rebased onto a newer target). */
    LOCAL_AHEAD,
    /** Remote base is strictly ahead of local base (remote rebased onto a newer target). */
    REMOTE_AHEAD,
    /** Bases have no ancestor relationship (only possible with a branched target history). */
    UNRELATED,
}

/**
 * Pure dispatcher for `jaspr pull`. Given pre-classified inputs, picks the action that the executor
 * should perform.
 *
 * @param local the local stack, ordered base-to-tip
 * @param remote the remote stack, ordered base-to-tip
 * @param remoteTipSha SHA the remote stack ref currently points at; used for `reset --hard` and as
 *   the target of `cherry-pick LO` operations
 * @param baseRelation pre-computed relationship between the two stack bases in the target ref's
 *   history
 * @param divergedCommitIds commit-ids that the classifier flagged as DIVERGED on the two sides
 *   (same id, different content or message)
 */
fun getPullPlan(
    local: List<Commit>,
    remote: List<Commit>,
    remoteTipSha: String,
    baseRelation: BaseRelation,
    divergedCommitIds: Set<String>,
): PullPlan {
    val localIds = local.mapNotNull(Commit::id)
    val remoteIds = remote.mapNotNull(Commit::id)
    val localIdSet = localIds.toSet()
    val remoteIdSet = remoteIds.toSet()
    val lo = localIdSet - remoteIdSet
    val ro = remoteIdSet - localIdSet

    if (divergedCommitIds.isNotEmpty()) return PullPlan.Punt(PuntReason.DIVERGED)
    if (lo.isNotEmpty() && ro.isNotEmpty()) return PullPlan.Punt(PuntReason.MIXED_UNIQUE_WORK)
    if (baseRelation == BaseRelation.UNRELATED) return PullPlan.Punt(PuntReason.UNRELATED_BASES)

    if (lo.isEmpty() && ro.isEmpty() && localIds != remoteIds) {
        return PullPlan.NoOp(NoOpReason.PURE_REORDERING)
    }

    val sharedShasMatch = sharedShasMatch(local, remote, localIdSet intersect remoteIdSet)
    val remoteHasFreshState =
        ro.isNotEmpty() ||
            baseRelation == BaseRelation.REMOTE_AHEAD ||
            (baseRelation == BaseRelation.EQUAL && !sharedShasMatch)

    return when {
        baseRelation == BaseRelation.LOCAL_AHEAD ->
            if (ro.isNotEmpty()) {
                PullPlan.CherryPickRoOntoLocalHead(remote.filter { it.id in ro })
            } else {
                PullPlan.NoOp(NoOpReason.LOCAL_AHEAD)
            }
        remoteHasFreshState ->
            if (lo.isEmpty()) {
                PullPlan.HardResetToRemoteTip(remoteTipSha)
            } else {
                PullPlan.CherryPickLoOntoRemoteTip(local.filter { it.id in lo }, remoteTipSha)
            }
        else -> {
            val reason = if (lo.isEmpty()) NoOpReason.UP_TO_DATE else NoOpReason.LOCAL_HAS_UNPUSHED
            PullPlan.NoOp(reason)
        }
    }
}

private fun sharedShasMatch(
    local: List<Commit>,
    remote: List<Commit>,
    sharedIds: Set<String>,
): Boolean {
    if (sharedIds.isEmpty()) return true
    val localById = local.filter { it.id != null }.associateBy { checkNotNull(it.id) }
    val remoteById = remote.filter { it.id != null }.associateBy { checkNotNull(it.id) }
    return sharedIds.all { id -> localById[id]?.hash == remoteById[id]?.hash }
}
