package sims.michael.gitjaspr

/*
 * Data model and selection logic for `jaspr compare <ref-or-index>...` and `jaspr compare -p`,
 * which diff commits' local and remote versions with rebase-only changes removed. Builds on the
 * row alignment from alignStacks.
 */

/** File name the commit message diff is labeled with, under each side's prefix. */
const val COMMIT_MESSAGE_LABEL = "COMMIT_MESSAGE"

enum class StackSide(val label: String) {
    LOCAL("local"),
    REMOTE("remote"),
}

data class SideCommit(val side: StackSide, val commit: Commit)

sealed interface CommitComparison {
    /** The row index shown by `jaspr compare`. */
    val index: Int

    data class Identical(override val index: Int, val local: Commit, val remote: Commit) :
        CommitComparison

    /** The commit exists on only one side, so there is nothing to diff it against. */
    data class OneSided(override val index: Int, val commit: Commit, val side: StackSide) :
        CommitComparison

    /**
     * The two versions differ. [left] is the old side of the diff and [right] the new side. Without
     * `--reverse` the newer version is on the right, falling back to remote on the left when
     * [newerSide] is null (both committed in the same second).
     */
    data class Diverged(
        override val index: Int,
        val left: SideCommit,
        val right: SideCommit,
        val newerSide: StackSide?,
    ) : CommitComparison
}

/**
 * Resolves [selector] to a row index from [rows]. An integer within the range of row indexes is
 * treated as an index. Anything else is resolved to a commit SHA by [resolveSha] and matched
 * against either side of each row.
 *
 * Git won't resolve an abbreviated SHA shorter than four characters, so the index interpretation
 * can only shadow a SHA in a stack of 1000 or more rows.
 */
fun resolveCompareIndex(
    rows: List<CompareRow>,
    selector: String,
    resolveSha: (String) -> String?,
): Int {
    val maxIndex = rows.maxOfOrNull(CompareRow::index) ?: 0
    val index = selector.toIntOrNull()?.takeIf { it in 1..maxIndex }
    if (index != null) return index
    val sha =
        resolveSha(selector)
            ?: throw GitJasprException(
                "'$selector' is neither a row index from `jaspr compare` (1-$maxIndex) nor a " +
                    "commit."
            )
    return rows.firstOrNull { row -> row.local?.hash == sha || row.remote?.hash == sha }?.index
        ?: throw GitJasprException(
            "Commit ${sha.take(7)} isn't in the local stack or the remote named stack."
        )
}

/**
 * Returns the index of every row that isn't [CompareRelation.IDENTICAL], in row order, for `jaspr
 * compare -p`. A reordered commit's two rows share an index, so it appears once.
 */
fun differingIndexes(rows: List<CompareRow>): List<Int> =
    rows
        .filter { row -> row.relation != CompareRelation.IDENTICAL }
        .map(CompareRow::index)
        .distinct()

/**
 * Builds the [CommitComparison] for the row(s) at [index]. A reordered commit appears in two rows
 * that share an index, one per side, so both sides are gathered from every matching row.
 */
fun buildCommitComparison(
    rows: List<CompareRow>,
    index: Int,
    reverse: Boolean = false,
): CommitComparison {
    val matching = rows.filter { row -> row.index == index }
    require(matching.isNotEmpty()) { "No compare row with index $index" }
    val local = matching.firstNotNullOfOrNull(CompareRow::local)
    val remote = matching.firstNotNullOfOrNull(CompareRow::remote)
    return when {
        local == null -> CommitComparison.OneSided(index, checkNotNull(remote), StackSide.REMOTE)
        remote == null -> CommitComparison.OneSided(index, local, StackSide.LOCAL)
        matching.first().relation == CompareRelation.IDENTICAL ->
            CommitComparison.Identical(index, local, remote)
        else -> {
            val newerSide =
                when (matching.first().relation) {
                    CompareRelation.DIVERGED_LOCAL_NEWER -> StackSide.LOCAL
                    CompareRelation.DIVERGED_REMOTE_NEWER -> StackSide.REMOTE
                    else -> null
                }
            val localSide = SideCommit(StackSide.LOCAL, local)
            val remoteSide = SideCommit(StackSide.REMOTE, remote)
            val newerOnRight =
                if (newerSide == StackSide.REMOTE) {
                    localSide to remoteSide
                } else {
                    remoteSide to localSide
                }
            val (left, right) =
                if (reverse) {
                    newerOnRight.second to newerOnRight.first
                } else {
                    newerOnRight
                }
            CommitComparison.Diverged(index, left, right, newerSide)
        }
    }
}
