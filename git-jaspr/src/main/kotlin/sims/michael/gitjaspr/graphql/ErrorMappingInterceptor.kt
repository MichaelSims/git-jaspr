package sims.michael.gitjaspr.graphql

import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import sims.michael.gitjaspr.GitJasprException

class ErrorMappingInterceptor : HttpInterceptor {

    override suspend fun intercept(
        request: HttpRequest,
        chain: HttpInterceptorChain,
    ): HttpResponse {
        val response = chain.proceed(request)
        if (response.statusCode == 401) {
            throw GitJasprException("GitHub authorization failed, please check your token")
        }
        return response
    }
}
