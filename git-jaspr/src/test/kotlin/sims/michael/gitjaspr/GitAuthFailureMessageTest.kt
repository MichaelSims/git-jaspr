package sims.michael.gitjaspr

import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class GitAuthFailureMessageTest {

    @Test
    fun `https password-auth rejection produces actionable guidance`() {
        // The exact shape git emits when a credential helper sends a username/password (or a stale
        // token) that GitHub rejects.
        val output =
            """
            remote: Invalid username or token. Password authentication is not supported for Git operations.
            fatal: Authentication failed for 'https://github.com/TrilliantHealth/engineering-backend.git/'
            """
                .trimIndent()

        val message = assertNotNull(gitAuthFailureMessageOrNull(output))
        assertContains(message, "Authentication to the git remote failed.")
        assertContains(message, "~/.jaspr.properties")
        assertContains(message, "authenticate over HTTPS")
        // Not logged in to gh, so we point them at logging in first.
        assertContains(message, "gh auth login && gh auth setup-git")
        assertContains(message, "git remote set-url")
    }

    @Test
    fun `https failure recommends gh auth setup-git directly when gh is logged in`() {
        val output =
            "fatal: Authentication failed for 'https://github.com/TrilliantHealth/repo.git/'"

        val message = assertNotNull(gitAuthFailureMessageOrNull(output) { true })
        assertContains(message, "gh auth setup-git")
        // When gh is already authenticated we should not tell them to log in again.
        assertFalse(
            message.contains("gh auth login"),
            "should not suggest `gh auth login` when gh is already authenticated",
        )
    }

    @Test
    fun `terminal-prompts-disabled failure is recognized as an auth failure`() {
        // What git emits under GIT_TERMINAL_PROMPT=0 when no credential helper supplies credentials
        // (i.e. the fail-fast path instead of an interactive prompt).
        val output =
            "fatal: could not read Username for 'https://github.com': terminal prompts disabled"

        val message = assertNotNull(gitAuthFailureMessageOrNull(output))
        assertContains(message, "authenticate over HTTPS")
    }

    @Test
    fun `ssh permission-denied failure produces ssh-specific guidance`() {
        val output =
            """
            git@github.com: Permission denied (publickey).
            fatal: Could not read from remote repository.
            """
                .trimIndent()

        val message = assertNotNull(gitAuthFailureMessageOrNull(output))
        assertContains(message, "SSH key")
        assertContains(message, "connecting-to-github-with-ssh")
        // SSH guidance should not push HTTPS / gh credential-helper advice.
        assertFalse(
            message.contains("gh auth setup-git"),
            "ssh guidance should not recommend the HTTPS credential helper",
        )
    }

    @Test
    fun `non-fast-forward rejection is not treated as an auth failure`() {
        val output =
            """
            To github.com:owner/repo.git
             ! [rejected]        main -> main (non-fast-forward)
            error: failed to push some refs to 'github.com:owner/repo.git'
            hint: Updates were rejected because the tip of your current branch is behind its remote.
            """
                .trimIndent()

        assertNull(gitAuthFailureMessageOrNull(output))
    }

    @Test
    fun `unrelated command failure is not treated as an auth failure`() {
        assertNull(gitAuthFailureMessageOrNull("fatal: not a git repository"))
    }
}
