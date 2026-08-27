package sims.michael.gitjaspr.githubtests

import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.GitHubStacksClient
import sims.michael.gitjaspr.StackInfo

class GitHubStacksStubClient : GitHubStacksClient {

    private val logger = LoggerFactory.getLogger(GitHubStacksStubClient::class.java)
    private val stacks = mutableListOf<StackInfo>()
    private val stackNumberIterator = (1..Int.MAX_VALUE).iterator()

    val allStacks: List<StackInfo>
        get() = synchronized(stacks) { stacks.toList() }

    override suspend fun isAvailable() = true

    override suspend fun findStackByPr(prNumber: Int): StackInfo? =
        synchronized(stacks) {
            stacks.firstOrNull { it.open && prNumber in it.pullRequestNumbers }
        }

    override suspend fun createStack(prNumbers: List<Int>): StackInfo {
        logger.trace("createStack {}", prNumbers)
        val info =
            StackInfo(
                number = stackNumberIterator.nextInt(),
                open = true,
                pullRequestNumbers = prNumbers,
            )
        synchronized(stacks) { stacks.add(info) }
        return info
    }

    override suspend fun unstack(stackNumber: Int) {
        logger.trace("unstack {}", stackNumber)
        synchronized(stacks) {
            val index = stacks.indexOfFirst { it.number == stackNumber }
            if (index >= 0) {
                stacks[index] = stacks[index].copy(open = false)
            }
        }
    }
}
