package sims.michael.gitjaspr

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class StackInfo(
    val number: Int,
    val open: Boolean,
    val pullRequestNumbers: List<Int>,
)

interface GitHubStacksClient {
    suspend fun isAvailable(): Boolean

    suspend fun findStackByPr(prNumber: Int): StackInfo?

    suspend fun createStack(prNumbers: List<Int>): StackInfo

    suspend fun unstack(stackNumber: Int)
}

class GitHubStacksClientImpl(
    private val httpClient: HttpClient,
    private val config: Config,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : GitHubStacksClient {

    private val logger = LoggerFactory.getLogger(GitHubStacksClient::class.java)

    private val baseUrl: String
        get() {
            val info = config.gitHubInfo
            val host =
                if (info.host == "github.com") {
                    "api.github.com"
                } else {
                    info.host
                }
            return "https://$host/repos/${info.owner}/${info.name}"
        }

    override suspend fun isAvailable(): Boolean {
        val response =
            httpClient.get("$baseUrl/stacks") {
                accept(ContentType.Application.Json)
                header("X-GitHub-Api-Version", API_VERSION)
                parameter("per_page", 1)
            }
        return response.status.isSuccess()
    }

    override suspend fun findStackByPr(prNumber: Int): StackInfo? {
        val response =
            httpClient.get("$baseUrl/stacks") {
                accept(ContentType.Application.Json)
                header("X-GitHub-Api-Version", API_VERSION)
                parameter("pull_request", prNumber)
            }
        if (!response.status.isSuccess()) return null
        val body = response.bodyAsText()
        val stacks = json.decodeFromString<List<StackResponse>>(body)
        return stacks.firstOrNull()?.toStackInfo()
    }

    override suspend fun createStack(prNumbers: List<Int>): StackInfo {
        logger.debug("Creating GitHub stack with PRs {}", prNumbers)
        val requestBody =
            json.encodeToString(CreateStackRequest.serializer(), CreateStackRequest(prNumbers))
        val response =
            httpClient.post("$baseUrl/stacks") {
                accept(ContentType.Application.Json)
                header("X-GitHub-Api-Version", API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.warn("Failed to create GitHub stack: {} {}", response.status, errorBody)
            throw GitJasprException("Failed to create GitHub stack: ${response.status}")
        }
        val body = response.bodyAsText()
        return json.decodeFromString<StackResponse>(body).toStackInfo()
    }

    override suspend fun unstack(stackNumber: Int) {
        logger.debug("Dissolving GitHub stack {}", stackNumber)
        val response =
            httpClient.post("$baseUrl/stacks/$stackNumber/unstack") {
                accept(ContentType.Application.Json)
                header("X-GitHub-Api-Version", API_VERSION)
            }
        if (!response.status.isSuccess() && response.status != HttpStatusCode.NoContent) {
            val errorBody = response.bodyAsText()
            logger.warn(
                "Failed to unstack GitHub stack {}: {} {}",
                stackNumber,
                response.status,
                errorBody,
            )
        }
    }

    @Suppress("PropertyName")
    @Serializable
    private data class CreateStackRequest(val pull_requests: List<Int>)

    @Suppress("PropertyName")
    @Serializable
    private data class StackResponse(
        val number: Int,
        val open: Boolean,
        val pull_requests: List<StackPrResponse>,
    ) {
        fun toStackInfo() =
            StackInfo(
                number = number,
                open = open,
                pullRequestNumbers = pull_requests.map(StackPrResponse::number),
            )
    }

    @Serializable
    private data class StackPrResponse(
        val number: Int,
        val state: String,
    )

    companion object {
        const val API_VERSION = "2026-03-10"
    }
}

class NoOpGitHubStacksClient : GitHubStacksClient {
    override suspend fun isAvailable() = false

    override suspend fun findStackByPr(prNumber: Int): StackInfo? = null

    override suspend fun createStack(prNumbers: List<Int>) =
        throw UnsupportedOperationException("GitHub Stacks not available")

    override suspend fun unstack(stackNumber: Int) {}
}
