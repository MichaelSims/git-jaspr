package sims.michael.gitjaspr

import com.expediagroup.graphql.client.GraphQLClient
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode

class ErrorMappingGraphQLClient<RequestCustomizer>(
    private val delegate: GraphQLClient<RequestCustomizer>,
) : GraphQLClient<RequestCustomizer> {
    override suspend fun <T : Any> execute(
        request: GraphQLClientRequest<T>,
        requestCustomizer: RequestCustomizer.() -> Unit,
    ): GraphQLClientResponse<T> = try {
        delegate.execute(request, requestCustomizer)
    } catch (e: ClientRequestException) {
        mapException(e)
    }

    override suspend fun execute(
        requests: List<GraphQLClientRequest<*>>,
        requestCustomizer: RequestCustomizer.() -> Unit,
    ): List<GraphQLClientResponse<*>> = try {
        delegate.execute(requests, requestCustomizer)
    } catch (e: ClientRequestException) {
        mapException(e)
    }

    private fun mapException(e: ClientRequestException): Nothing {
        if (e.response.status == HttpStatusCode.Unauthorized) {
            throw GitJasprException("GitHub authorization failed, please check your token", e)
        } else {
            throw e
        }
    }
}
