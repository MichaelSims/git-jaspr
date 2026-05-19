package sims.michael.gitjaspr

import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim

/**
 * Throwaway data model + renderer for previewing a proposed `jaspr compare` output in a real
 * terminal. Hand-rolled rows let the preview tests vary highlight modes without committing to any
 * production rendering. Replaced when `compare` actually ships.
 */
internal enum class CompareHighlight {
    /** Direction in the marker column (`<~~` / `~~>`); no per-cell styling. */
    ARROWS,
    /** Asterisk in a dedicated column on the newer side; symmetric marker (`~~`). */
    ASTERISK,
    /** Dim the older side; symmetric marker. */
    DIM_OLDER,
    /** Bold the newer side; symmetric marker. */
    BOLD_NEWER,
    /** Both: asterisk on newer + dim on older; symmetric marker. */
    ASTERISK_AND_DIM,
    /** Everything: bold newer + asterisk on newer + dim older; symmetric marker. */
    BOLD_ASTERISK_DIM,
}

internal enum class CompareRelation {
    IDENTICAL,
    DIVERGED_LOCAL_NEWER,
    DIVERGED_REMOTE_NEWER,
    DIVERGED_EQUAL_DATE,
    LOCAL_ONLY,
    REMOTE_ONLY,
}

internal data class CompareCommit(val sha: String, val subject: String)

internal data class CompareRow(
    val index: Int,
    val local: CompareCommit?,
    val remote: CompareCommit?,
    val relation: CompareRelation,
)

internal fun renderComparePreview(
    rows: List<CompareRow>,
    stackName: String,
    remoteName: String,
    highlight: CompareHighlight,
    theme: Theme,
): String = buildString {
    val withAsterisks =
        highlight in
            setOf(
                CompareHighlight.ASTERISK,
                CompareHighlight.ASTERISK_AND_DIM,
                CompareHighlight.BOLD_ASTERISK_DIM,
            )
    val withDim =
        highlight in
            setOf(
                CompareHighlight.DIM_OLDER,
                CompareHighlight.ASTERISK_AND_DIM,
                CompareHighlight.BOLD_ASTERISK_DIM,
            )
    val withBold =
        highlight in setOf(CompareHighlight.BOLD_NEWER, CompareHighlight.BOLD_ASTERISK_DIM)
    val withArrows = highlight == CompareHighlight.ARROWS

    val markerWidth = 5
    val leftWidth = rows.maxOf { row -> formatCell(row, isLocal = true, withAsterisks).length } + 2

    val rightHeader = "REMOTE ($remoteName/jaspr/.../$stackName)"
    appendLine(theme.heading("LOCAL".padEnd(leftWidth + markerWidth) + rightHeader))
    appendLine()

    for (row in rows) {
        val leftRaw = formatCell(row, isLocal = true, withAsterisks)
        val rightRaw = formatCell(row, isLocal = false, withAsterisks)
        val marker = computeMarker(row.relation, withArrows)

        val leftStyled =
            styleCell(leftRaw.padEnd(leftWidth), row, isLocal = true, withDim, withBold)
        val rightStyled = styleCell(rightRaw, row, isLocal = false, withDim, withBold)

        appendLine("$leftStyled${marker.padEnd(markerWidth)}$rightStyled")
    }
}

private fun formatCell(row: CompareRow, isLocal: Boolean, withAsterisks: Boolean): String {
    val commit = if (isLocal) row.local else row.remote
    if (commit == null) return ""
    val annotation =
        when {
            row.relation == CompareRelation.LOCAL_ONLY && isLocal -> "  [local-only]"
            row.relation == CompareRelation.REMOTE_ONLY && !isLocal -> "  [remote-only]"
            else -> ""
        }
    val newer =
        (row.relation == CompareRelation.DIVERGED_LOCAL_NEWER && isLocal) ||
            (row.relation == CompareRelation.DIVERGED_REMOTE_NEWER && !isLocal)
    val asteriskCol = if (withAsterisks) (if (newer) "* " else "  ") else ""
    return "[${row.index}] $asteriskCol${commit.sha.take(7)} ${commit.subject}$annotation"
}

private fun computeMarker(relation: CompareRelation, withArrows: Boolean): String =
    when (relation) {
        CompareRelation.IDENTICAL -> "=="
        CompareRelation.DIVERGED_LOCAL_NEWER -> if (withArrows) "<~~" else "~~"
        CompareRelation.DIVERGED_REMOTE_NEWER -> if (withArrows) "~~>" else "~~"
        CompareRelation.DIVERGED_EQUAL_DATE -> "~~"
        CompareRelation.LOCAL_ONLY,
        CompareRelation.REMOTE_ONLY -> ""
    }

private fun styleCell(
    text: String,
    row: CompareRow,
    isLocal: Boolean,
    withDim: Boolean,
    withBold: Boolean,
): String {
    val isOlder =
        when (row.relation) {
            CompareRelation.DIVERGED_LOCAL_NEWER -> !isLocal
            CompareRelation.DIVERGED_REMOTE_NEWER -> isLocal
            else -> false
        }
    val isNewer =
        when (row.relation) {
            CompareRelation.DIVERGED_LOCAL_NEWER -> isLocal
            CompareRelation.DIVERGED_REMOTE_NEWER -> !isLocal
            else -> false
        }
    var styled = text
    if (withDim && isOlder) styled = dim(styled)
    if (withBold && isNewer) styled = bold(styled)
    return styled
}
