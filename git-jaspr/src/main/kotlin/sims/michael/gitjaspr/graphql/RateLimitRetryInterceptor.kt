package sims.michael.gitjaspr.graphql

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import org.slf4j.LoggerFactory

class RateLimitRetryInterceptor(private val delays: List<Long> = DEFAULT_DELAYS) :
    ApolloInterceptor {

    init {
        check(delays.isNotEmpty())
    }

    private val logger = LoggerFactory.getLogger(RateLimitRetryInterceptor::class.java)

    override fun <D : Operation.Data> intercept(
        request: ApolloRequest<D>,
        chain: ApolloInterceptorChain,
    ): Flow<ApolloResponse<D>> = flow {
        var attemptsMade = 0
        var response: ApolloResponse<D>
        do {
            val delayMs = delays[attemptsMade]
            if (delayMs > 0) {
                logger.info(
                    "Delaying {} due to GitHub API throttling...",
                    delayMs.toDuration(DurationUnit.MILLISECONDS),
                )
                delay(delayMs)
            }
            response = chain.proceed(request).single()
            attemptsMade++
        } while (attemptsMade < delays.size && response.isRateLimitError())
        emit(response)
    }

    private fun ApolloResponse<*>.isRateLimitError(): Boolean =
        errors.orEmpty().any { it.message == "was submitted too quickly" }

    companion object {
        private val DEFAULT_DELAYS = listOf(0L, 60_000, 90_000, 120_000)
    }
}
