package sims.michael.gitjaspr

import kotlin.random.Random

object StackNameGenerator {

    private const val MAX_LENGTH = 40

    /**
     * Generates a stack name from the given commit subject. The result is a lowercase,
     * hyphen-separated string suitable for use as a branch name component, or an empty string if
     * the subject contains no alphanumeric characters.
     */
    fun generateName(subject: String): String =
        subject
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .let { string -> truncateAtWordBoundary(string, MAX_LENGTH) }

    /** Generates a random 4-letter suffix for use in collision resolution. */
    fun generateSuffix(random: Random = Random.Default): String {
        val chars = "abcdefghijklmnopqrstuvwxyz"
        return (1..4).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun truncateAtWordBoundary(
        name: String,
        @Suppress("SameParameterValue") maxLength: Int,
    ): String {
        if (name.length <= maxLength) return name
        val truncated = name.substring(0, maxLength)
        val lastHyphen = truncated.lastIndexOf('-')
        return if (lastHyphen > 0) truncated.substring(0, lastHyphen) else truncated
    }
}
