package sims.michael.gitjaspr

import sims.michael.gitjaspr.CliGitClient.Companion.GIT_FORMAT_SEPARATOR
import sims.michael.gitjaspr.CliGitClient.Companion.GIT_LOG_TRAILER_SEPARATOR
import java.time.Instant
import java.time.ZoneId

object CommitParsers {

    data class SubjectAndBody(val subject: String, val body: String?)

    fun getSubjectAndBodyFromFullMessage(fullMessage: String): SubjectAndBody {
        return SubjectAndBody(
            fullMessage.substringBefore("\n\n").replace("\n", " ").trim(),
            if (fullMessage.contains("\n\n")) {
                fullMessage.substringAfter("\n\n").trim().takeIf(String::isNotBlank)
            } else {
                null
            },
        )
    }

    fun parseCommitLogEntry(logEntry: String): Commit {
        val split = logEntry.split(GIT_FORMAT_SEPARATOR)
        check(split.size == 8) {
            "Log entry is in unexpected format: $logEntry"
        }
        val (firstChunk, secondChunk) = split.chunked(5)
        val (hash, shortMessage, committerName, committerEmail, commitIds) = firstChunk
        val (commitTimestamp, authorTimestamp, fullMessage) = secondChunk

        fun String.timestampToZonedDateTime() = Instant
            .ofEpochSecond(toLong())
            .atZone(ZoneId.systemDefault())
            .canonicalize()

        return Commit(
            hash,
            shortMessage,
            fullMessage,
            commitIds.split(GIT_LOG_TRAILER_SEPARATOR).last().takeUnless(String::isBlank),
            Ident(committerName, committerEmail),
            commitTimestamp.timestampToZonedDateTime(),
            authorTimestamp.timestampToZonedDateTime(),
        )
    }

    fun addFooters(fullMessage: String, footers: Map<String, String>): String {
        val existingFooters = getFooters(fullMessage)
        return if (existingFooters.isNotEmpty()) {
            fullMessage.trim() + "\n" + footers.map { (k, v) -> "$k: $v" }.joinToString("\n") + "\n"
        } else {
            fullMessage.trim() + "\n\n" + footers.map { (k, v) -> "$k: $v" }.joinToString("\n") + "\n"
        }
    }

    fun getFooters(fullMessage: String): Map<String, String> {
        val fullMessageTrimmed = fullMessage.trim()
        val maybeFooterSection = fullMessageTrimmed.substringAfterLast("\n\n")
        if (maybeFooterSection == fullMessageTrimmed) return emptyMap() // Just a subject

        val footerLineRegex = "^([^\\s:]+): ([^\\s:]+)$".toRegex()

        val maybeFooterLines = maybeFooterSection.lines()
        return if (maybeFooterLines.all { line -> footerLineRegex.matches(line) }) {
            maybeFooterLines.associate { line ->
                val (key, value) = line.split(":")
                key.trim() to value.trim()
            }
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
