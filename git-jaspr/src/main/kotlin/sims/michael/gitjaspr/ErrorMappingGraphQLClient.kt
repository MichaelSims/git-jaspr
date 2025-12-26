package sims.michael.gitjaspr

import com.expediagroup.graphql.client.GraphQLClient
import com.expediagroup.graphql.client.types.GraphQLClientError
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode

class ErrorMappingGraphQLClient<RequestCustomizer>(
    private val delegate: GraphQLClient<RequestCustomizer>
) : GraphQLClient<RequestCustomizer> {
    override suspend fun <T : Any> execute(
        request: GraphQLClientRequest<T>,
        requestCustomizer: RequestCustomizer.() -> Unit,
    ): GraphQLClientResponse<T> =
        try {
            delegate.execute(request, requestCustomizer).also(::failIfAnyErrors)
        } catch (e: ClientRequestException) {
            mapClientRequestException(e)
        }

    override suspend fun execute(
        requests: List<GraphQLClientRequest<*>>,
        requestCustomizer: RequestCustomizer.() -> Unit,
    ): List<GraphQLClientResponse<*>> =
        try {
            delegate.execute(requests, requestCustomizer).onEach(::failIfAnyErrors)
        } catch (e: ClientRequestException) {
            mapClientRequestException(e)
        }

    private fun mapClientRequestException(e: ClientRequestException): Nothing {
        if (e.response.status == HttpStatusCode.Unauthorized) {
            throw GitJasprException("GitHub authorization failed, please check your token", e)
        } else {
            throw e
        }
    }

    private fun failIfAnyErrors(response: GraphQLClientResponse<*>) {
        val errors = response.errors.orEmpty()
        if (errors.isNotEmpty()) {
            throw GitJasprException(
                "GraphQL request failed with errors: ${errors.map(GraphQLClientError::message)}"
            )
        }
    }
}
