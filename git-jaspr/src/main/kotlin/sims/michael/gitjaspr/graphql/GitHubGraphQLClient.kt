package sims.michael.gitjaspr.graphql

import com.expediagroup.graphql.client.GraphQLClient
import com.expediagroup.graphql.client.types.GraphQLClientError
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import io.ktor.client.request.*
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class GitHubGraphQLClient(private val delegate: GraphQLClient<HttpRequestBuilder>) : GraphQLClient<HttpRequestBuilder> {

    private val logger = LoggerFactory.getLogger(GitHubGraphQLClient::class.java)

    override suspend fun <T : Any> execute(
        request: GraphQLClientRequest<T>,
        requestCustomizer: HttpRequestBuilder.() -> Unit,
    ): GraphQLClientResponse<T> {
        lateinit var response: GraphQLClientResponse<T>
        var attemptsMade = 0
        do {
            val delay = DELAYS[attemptsMade]
            if (delay > 0) {
                logger.info("Delaying {} due to GitHub API throttling...", delay.toDuration(DurationUnit.MILLISECONDS))
                delay(delay)
            }
            response = delegate.execute(request, requestCustomizer)
            attemptsMade++
        } while (attemptsMade < MAX_ATTEMPTS && response.isRateLimitError())
        return response
    }

    private fun GraphQLClientResponse<*>.isRateLimitError() =
        errors.orEmpty().map(GraphQLClientError::message).any { it == "was submitted too quickly" }

    override suspend fun execute(
        requests: List<GraphQLClientRequest<*>>,
        requestCustomizer: HttpRequestBuilder.() -> Unit,
    ): List<GraphQLClientResponse<*>> = throw NotImplementedError() // The GitHub API doesn't support batched requests

    companion object {
        private const val MAX_ATTEMPTS = 4
        private val DELAYS = listOf(0L, 60_000, 90_000, 120_000)

        init {
            check(DELAYS.size == MAX_ATTEMPTS)
        }
    }
}
