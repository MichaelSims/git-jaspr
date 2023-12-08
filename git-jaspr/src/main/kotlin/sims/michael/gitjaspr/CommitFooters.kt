package sims.michael.gitjaspr

object CommitFooters {
    fun addFooters(fullMessage: String, footers: Map<String, String>): String {
        val existingFooters = getFooters(fullMessage)
        return if (existingFooters.isNotEmpty()) {
            fullMessage.trim() + "\n" + footers.map { (k, v) -> "$k: $v" }.joinToString("\n") + "\n"
        } else {
            fullMessage.trim() + "\n\n" + footers.map { (k, v) -> "$k: $v" }.joinToString("\n") + "\n"
        }
    }

    fun getFooters(fullMessage: String): Map<String, String> {
        val maybeFooterSection = fullMessage.trim().substringAfterLast("\n\n")
        val maybeFooterLines = maybeFooterSection.lines().map { line -> line.split("\\s*:\\s*".toRegex()) }
        return if (maybeFooterLines.all { list -> list.size == 2 }) {
            maybeFooterLines.associate { (k, v) -> k to v }
        } else {
            emptyMap()
        }
    }

    fun trimFooters(fullMessage: String): String {
        return if (getFooters(fullMessage).isNotEmpty()) {
            fullMessage.substringBeforeLast("\n\n") + "\n"
        } else {
            fullMessage
        }
    }
}
