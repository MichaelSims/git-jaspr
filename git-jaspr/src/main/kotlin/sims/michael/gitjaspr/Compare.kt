package sims.michael.gitjaspr

/**
 * Data model and rendering for `jaspr compare`. Pairs the local stack and the remote named stack
 * row-by-row using LCS alignment, classifies each pair as content-identical or content-diverged,
 * and renders the result in a two-column layout. See ADR 0002 for the rationale and rejected
 * alternatives.
 */
enum class CompareRelation {
    /** Both sides have the same commit-id and content (different SHA allowed; rebase counts). */
    IDENTICAL,
    /** Same commit-id, content diverged, local commit date is strictly newer. */
    DIVERGED_LOCAL_NEWER,
    /** Same commit-id, content diverged, remote commit date is strictly newer. */
    DIVERGED_REMOTE_NEWER,
    /** Same commit-id, content diverged, both committed in the same wall-clock second. */
    DIVERGED_EQUAL_DATE,
    /** Commit-id exists only in the local stack. */
    LOCAL_ONLY,
    /** Commit-id exists only in the remote stack. */
    REMOTE_ONLY,
}

data class CompareRow(
    val local: Commit?,
    val remote: Commit?,
    val relation: CompareRelation,
    val index: Int,
)

/**
 * Aligns two commit lists by jaspr commit-id and produces an ordered list of [CompareRow]s.
 *
 * Alignment uses the longest common subsequence of commit-ids as the spine: any items from the
 * list, in their original order, not necessarily adjacent in the original. LCS-aligned commits pair
 * up; everything else falls out as one-sided rows.
 *
 * Whether a one-sided row is "reordered" (its commit-id also appears on the other stack at a
 * different position) is derivable from the full row list. Both occurrences of a reordered
 * commit-id share the same row index, so the eye can connect them across the rendering.
 *
 * The [classifier] is consulted for any LCS-aligned pair to decide IDENTICAL vs. DIVERGED_*. Date
 * comparison decides which DIVERGED_* subtype.
 */
fun alignStacks(
    local: List<Commit>,
    remote: List<Commit>,
    classifier: DivergenceClassifier,
): List<CompareRow> {
    val localIds = local.mapNotNull(Commit::id)
    val remoteIds = remote.mapNotNull(Commit::id)
    val lcsIds = longestCommonSubsequence(localIds, remoteIds).toSet()
    val localById = local.filter { it.id != null }.associateBy { checkNotNull(it.id) }
    val remoteById = remote.filter { it.id != null }.associateBy { checkNotNull(it.id) }

    val rawRows = walkAlignment(local, remote, lcsIds)
    val classifiedAligned = classifyDivergedRows(rawRows, classifier)
    val classifiedReordered =
        classifyReorderedRows(classifiedAligned, localById, remoteById, classifier)
    // Render newest commit (tip) at the top to match `jaspr status`. Reversing before
    // assignIndexes also numbers from the tip, so [1] is the tip and indices ascend downward.
    return assignIndexes(classifiedReordered.reversed(), localIds.toSet(), remoteIds.toSet())
}

private fun walkAlignment(
    local: List<Commit>,
    remote: List<Commit>,
    spine: Set<String>,
): List<CompareRow> = buildList {
    var i = 0
    var j = 0
    while (i < local.size && j < remote.size) {
        val l = local[i]
        val r = remote[j]
        val lOnSpine = l.id != null && l.id in spine
        val rOnSpine = r.id != null && r.id in spine
        when {
            lOnSpine && rOnSpine && l.id == r.id -> {
                add(CompareRow(l, r, CompareRelation.IDENTICAL, 0))
                i++
                j++
            }
            !lOnSpine -> {
                add(CompareRow(l, null, CompareRelation.LOCAL_ONLY, 0))
                i++
            }
            // Either !rOnSpine, or both heads are on the spine but with mismatched ids (only
            // reachable if the LCS computation is wrong; safe fallback).
            else -> {
                add(CompareRow(null, r, CompareRelation.REMOTE_ONLY, 0))
                j++
            }
        }
    }
    local.drop(i).forEach { add(CompareRow(it, null, CompareRelation.LOCAL_ONLY, 0)) }
    remote.drop(j).forEach { add(CompareRow(null, it, CompareRelation.REMOTE_ONLY, 0)) }
}

private fun classifyDivergedRows(
    rows: List<CompareRow>,
    classifier: DivergenceClassifier,
): List<CompareRow> = rows.map { row ->
    if (row.relation == CompareRelation.IDENTICAL) {
        row.copy(
            relation =
                classifyAlignedPair(checkNotNull(row.local), checkNotNull(row.remote), classifier)
        )
    } else row
}

/**
 * Refines one-sided rows whose commit-id also exists on the opposite stack (i.e., reordered
 * commits). Calls [classifyAlignedPair] against the counterpart to set IDENTICAL vs. DIVERGED_* on
 * the row's [CompareRow.relation]. The row stays one-sided: [CompareRow.local] /
 * [CompareRow.remote] are not modified, so the renderer still draws the row in only the original
 * position.
 */
private fun classifyReorderedRows(
    rows: List<CompareRow>,
    localById: Map<String, Commit>,
    remoteById: Map<String, Commit>,
    classifier: DivergenceClassifier,
): List<CompareRow> = rows.map { row ->
    val local = row.local
    val remote = row.remote
    val pair =
        when {
            local != null && remote == null ->
                local.id?.let(remoteById::get)?.let { counterpart -> local to counterpart }
            local == null && remote != null ->
                remote.id?.let(localById::get)?.let { counterpart -> counterpart to remote }
            else -> null
        }
    if (pair == null) {
        row
    } else {
        row.copy(relation = classifyAlignedPair(pair.first, pair.second, classifier))
    }
}

private fun classifyAlignedPair(
    local: Commit,
    remote: Commit,
    classifier: DivergenceClassifier,
): CompareRelation =
    if (local.hash == remote.hash) {
        CompareRelation.IDENTICAL
    } else {
        when (classifier.classify(local.hash, remote.hash)) {
            DivergenceClassifier.Result.IDENTICAL -> CompareRelation.IDENTICAL
            DivergenceClassifier.Result.DIVERGENT ->
                when {
                    local.commitDate > remote.commitDate -> CompareRelation.DIVERGED_LOCAL_NEWER
                    remote.commitDate > local.commitDate -> CompareRelation.DIVERGED_REMOTE_NEWER
                    else -> CompareRelation.DIVERGED_EQUAL_DATE
                }
        }
    }

/**
 * True if this row is one-sided AND its commit-id also exists on the opposite stack. Detection is
 * based on `local` / `remote` nullability, not `relation`, so reordered rows that have been
 * classified as DIVERGED_* (because they were also amended) are still recognized as reordered.
 */
private fun isReordered(row: CompareRow, localIds: Set<String>, remoteIds: Set<String>): Boolean {
    val local = row.local
    val remote = row.remote
    return when {
        local != null && remote == null -> local.id != null && local.id in remoteIds
        local == null && remote != null -> remote.id != null && remote.id in localIds
        else -> false
    }
}

private fun assignIndexes(
    rows: List<CompareRow>,
    localIds: Set<String>,
    remoteIds: Set<String>,
): List<CompareRow> {
    val idToIndex = mutableMapOf<String, Int>()
    var next = 1
    return rows.map { row ->
        val sharedId =
            if (isReordered(row, localIds, remoteIds)) row.local?.id ?: row.remote?.id else null
        val index =
            if (sharedId != null) {
                idToIndex.getOrPut(sharedId) { next++ }
            } else {
                next++
            }
        row.copy(index = index)
    }
}

/**
 * Standard O(m*n) DP longest-common-subsequence. Returns one valid LCS (not necessarily unique).
 */
private fun <T> longestCommonSubsequence(a: List<T>, b: List<T>): List<T> {
    if (a.isEmpty() || b.isEmpty()) return emptyList()
    val m = a.size
    val n = b.size
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in 1..m) {
        for (j in 1..n) {
            dp[i][j] =
                if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1
                else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
    }
    val out = ArrayDeque<T>()
    var i = m
    var j = n
    while (i > 0 && j > 0) {
        when {
            a[i - 1] == b[j - 1] -> {
                out.addFirst(a[i - 1])
                i--
                j--
            }
            dp[i - 1][j] >= dp[i][j - 1] -> i--
            else -> j--
        }
    }
    return out.toList()
}

/**
 * Renders rows in the BOLD_ASTERISK_DIM style accepted in ADR 0002.
 *
 * When [cursorSha] is non-null, the row whose LOCAL commit hash matches is rendered with
 * [Theme.emphasis] (bold in [DefaultTheme]) so it visibly pops without disturbing column width.
 */
fun renderCompare(
    rows: List<CompareRow>,
    namedStackRef: String,
    theme: Theme,
    maxSubjectLength: Int = DEFAULT_MAX_SUBJECT_LENGTH,
    cursorSha: String? = null,
): String = buildString {
    val localIds = rows.mapNotNull { it.local?.id }.toSet()
    val remoteIds = rows.mapNotNull { it.remote?.id }.toSet()
    val reorderedByRow = rows.associateWith { row -> isReordered(row, localIds, remoteIds) }

    val markerWidth = 5
    val leftWidth =
        rows
            .maxOfOrNull { row ->
                formatCell(row, isLocal = true, maxSubjectLength, reorderedByRow.getValue(row))
                    .length
            }
            ?.plus(2) ?: 0

    appendLine(theme.heading("LOCAL".padEnd(leftWidth + markerWidth) + "REMOTE ($namedStackRef)"))
    for (row in rows) {
        val isReorderedRow = reorderedByRow.getValue(row)
        val leftRaw = formatCell(row, isLocal = true, maxSubjectLength, isReorderedRow)
        val rightRaw = formatCell(row, isLocal = false, maxSubjectLength, isReorderedRow)
        val marker = computeMarker(row.relation, isReorderedRow)

        val leftStyled = styleCell(leftRaw.padEnd(leftWidth), row, isLocal = true, theme)
        val rightStyled = styleCell(rightRaw, row, isLocal = false, theme)

        val rowLine = "$leftStyled${marker.padEnd(markerWidth)}$rightStyled"
        appendLine(
            if (cursorSha != null && row.local?.hash == cursorSha) theme.emphasis(rowLine)
            else rowLine
        )
    }
}

/**
 * Default cap on commit subject length in the compare renderer. Subjects longer than this are
 * truncated with `…`. Tunable: pass a different value via `renderCompare(..., maxSubjectLength =
 * ...)`.
 */
const val DEFAULT_MAX_SUBJECT_LENGTH = 50

private fun truncateSubject(subject: String, max: Int): String =
    if (subject.length <= max) subject else subject.take(max - 1) + "…"

private fun formatCell(
    row: CompareRow,
    isLocal: Boolean,
    maxSubjectLength: Int,
    isReordered: Boolean,
): String {
    val commit = if (isLocal) row.local else row.remote
    if (commit == null) return ""
    val annotation =
        when {
            isReordered -> "  [reordered]"
            row.relation == CompareRelation.LOCAL_ONLY && isLocal -> "  [local-only]"
            row.relation == CompareRelation.REMOTE_ONLY && !isLocal -> "  [remote-only]"
            else -> ""
        }
    val newer = isNewerSide(row, isLocal)
    val asteriskCol = if (newer) "* " else "  "
    val subject = truncateSubject(commit.shortMessage, maxSubjectLength)
    return "[${row.index}] $asteriskCol${commit.hash.take(7)} $subject$annotation"
}

private fun computeMarker(relation: CompareRelation, isReordered: Boolean): String =
    if (isReordered) {
        ""
    } else {
        when (relation) {
            CompareRelation.IDENTICAL -> "=="
            CompareRelation.DIVERGED_LOCAL_NEWER,
            CompareRelation.DIVERGED_REMOTE_NEWER,
            CompareRelation.DIVERGED_EQUAL_DATE -> "~~"

            CompareRelation.LOCAL_ONLY,
            CompareRelation.REMOTE_ONLY -> ""
        }
    }

private fun styleCell(text: String, row: CompareRow, isLocal: Boolean, theme: Theme): String {
    val isOlder = isOlderSide(row, isLocal)
    val isNewer = isNewerSide(row, isLocal)
    var styled = text
    if (isOlder) styled = theme.muted(styled)
    if (isNewer) styled = theme.emphasis(styled)
    return styled
}

private fun isNewerSide(row: CompareRow, isLocal: Boolean): Boolean =
    when (row.relation) {
        CompareRelation.DIVERGED_LOCAL_NEWER -> isLocal
        CompareRelation.DIVERGED_REMOTE_NEWER -> !isLocal
        else -> false
    }

private fun isOlderSide(row: CompareRow, isLocal: Boolean): Boolean =
    when (row.relation) {
        CompareRelation.DIVERGED_LOCAL_NEWER -> !isLocal
        CompareRelation.DIVERGED_REMOTE_NEWER -> isLocal
        else -> false
    }
