package sims.michael.gitjaspr

import com.expediagroup.graphql.client.GraphQLClient
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import java.net.URI
import kotlinx.serialization.json.Json
import sims.michael.gitjaspr.graphql.GitHubGraphQLClient

interface AppWiring {
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
    val graphQLClient: GraphQLClient<*>
        get() = gitHubClientWiring.graphQLClient

    @Suppress("MemberVisibilityCanBePrivate")
    val gitHubClient: GitHubClient
        get() = gitHubClientWiring.gitHubClient

    override val json: Json = Json { prettyPrint = true }

    override val gitJaspr: GitJaspr by lazy { GitJaspr(gitHubClient, gitClient, config) }
}

class GitHubClientWiring(
    private val githubToken: String,
    private val gitHubInfo: GitHubInfo,
    private val remoteBranchPrefix: String,
    private val getPullRequestsPageSize: Int = GitHubClient.GET_PULL_REQUESTS_DEFAULT_PAGE_SIZE,
) {
    private val bearerTokens by lazy { BearerTokens(githubToken, githubToken) }

    private val httpClient by lazy {
        HttpClient(engineFactory = CIO).config {
            install(Auth) { bearer { loadTokens { bearerTokens } } }
        }
    }

    val graphQLClient: GraphQLClient<*> by lazy {
        ErrorMappingGraphQLClient(
            GitHubGraphQLClient(
                GraphQLKtorClient(URI.create("https://api.github.com/graphql").toURL(), httpClient)
            )
        )
    }

    val gitHubClient: GitHubClient by lazy {
        GitHubClientImpl(graphQLClient, gitHubInfo, remoteBranchPrefix, getPullRequestsPageSize)
    }
}
