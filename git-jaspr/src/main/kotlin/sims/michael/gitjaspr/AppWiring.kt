package sims.michael.gitjaspr

import com.apollographql.apollo.ApolloClient
import com.apollographql.ktor.http.KtorHttpEngine
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import java.io.Closeable
import kotlinx.serialization.json.Json
import sims.michael.gitjaspr.graphql.ErrorMappingInterceptor
import sims.michael.gitjaspr.graphql.RateLimitRetryInterceptor

interface AppWiring : Closeable {
    val gitJaspr: GitJaspr
    val config: Config
    val json: Json
    val gitClient: GitClient
}

class DefaultAppWiring(
    githubToken: String,
    override val config: Config,
    override val gitClient: GitClient,
) : AppWiring {

    private val gitHubClientWiring =
        GitHubClientWiring(githubToken, config.gitHubInfo, config.remoteBranchPrefix)

    @Suppress("unused")
    val apolloClient: ApolloClient
        get() = gitHubClientWiring.apolloClient

    @Suppress("MemberVisibilityCanBePrivate")
    val gitHubClient: GitHubClient
        get() = gitHubClientWiring.gitHubClient

    override val json: Json = Json { prettyPrint = true }

    override val gitJaspr: GitJaspr by lazy { GitJaspr(gitHubClient, gitClient, config) }

    override fun close() = gitHubClientWiring.close()
}

class GitHubClientWiring(
    private val githubToken: String,
    private val gitHubInfo: GitHubInfo,
    private val remoteBranchPrefix: String,
    private val getPullRequestsPageSize: Int = GitHubClient.GET_PULL_REQUESTS_DEFAULT_PAGE_SIZE,
) : Closeable {
    private val bearerTokens by lazy { BearerTokens(githubToken, githubToken) }

    private val httpClient by lazy {
        HttpClient(engineFactory = CIO).config {
            install(Auth) { bearer { loadTokens { bearerTokens } } }
        }
    }

    val apolloClient: ApolloClient by lazy {
        ApolloClient.Builder()
            .serverUrl("https://api.github.com/graphql")
            .httpEngine(KtorHttpEngine(httpClient))
            .addHttpInterceptor(ErrorMappingInterceptor())
            .addInterceptor(RateLimitRetryInterceptor())
            .build()
    }

    val gitHubClient: GitHubClient by lazy {
        GitHubClientImpl(apolloClient, gitHubInfo, remoteBranchPrefix, getPullRequestsPageSize)
    }

    override fun close() {
        apolloClient.close()
        httpClient.close()
    }
}
