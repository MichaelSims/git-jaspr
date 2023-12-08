package sims.michael.gitjaspr

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

class GitTest {
    @TestFactory
    fun `test extractGitHubInfoFromUri`(): List<DynamicTest> {
        fun test(name: String, remoteUri: String, expected: GitHubInfo) = dynamicTest(name) {
            assertEquals(expected, extractGitHubInfoFromUri(remoteUri))
        }
        return listOf(
            test(
                "SSH",
                "git@github.com:owner/name.git",
                GitHubInfo("github.com", "owner", "name"),
            ),
            test(
                "SSH with custom host",
                "git@github.example.com:owner/name.git",
                GitHubInfo("github.example.com", "owner", "name"),
            ),
            test(
                "HTTPS",
                "https://github.com/owner/name.git",
                GitHubInfo("github.com", "owner", "name"),
            ),
            test(
                "HTTPS with custom host",
                "https://github.example.com/owner/name.git",
                GitHubInfo("github.example.com", "owner", "name"),
            ),
        )
    }
}
