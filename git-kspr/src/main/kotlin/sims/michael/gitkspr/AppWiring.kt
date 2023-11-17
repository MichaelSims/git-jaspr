package sims.michael.gitkspr

import com.expediagroup.graphql.client.GraphQLClient
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import kotlinx.serialization.json.Json
import sims.michael.gitkspr.graphql.GitHubGraphQLClient
import java.net.URL

interface AppWiring {
    val gitKspr: GitKspr
    val config: Config
    val json: Json
    val gitClient: JGitClient
}

class DefaultAppWiring(
    githubToken: String,
    override val config: Config,
    override val gitClient: JGitClient,
) : AppWiring {

    private val gitHubClientWiring = GitHubClientWiring(githubToken, config.gitHubInfo, config.remoteBranchPrefix)

    val graphQLClient: GraphQLClient<*> get() = gitHubClientWiring.graphQLClient

    val gitHubClient: GitHubClient get() = gitHubClientWiring.gitHubClient

    override val json: Json = Json {
        prettyPrint = true
    }

    override val gitKspr: GitKspr by lazy { GitKspr(gitHubClient, gitClient, config) }
}

class GitHubClientWiring(
    private val githubToken: String,
    private val gitHubInfo: GitHubInfo,
    private val remoteBranchPrefix: String,
) {
    private val bearerTokens by lazy {
        BearerTokens(githubToken, githubToken)
    }

    private val httpClient by lazy {
        HttpClient(engineFactory = CIO)
            .config {
                install(Auth) {
                    bearer {
                        loadTokens {
                            bearerTokens
                        }
                    }
                }
            }
    }

    val graphQLClient: GraphQLClient<*> by lazy {
        GitHubGraphQLClient(GraphQLKtorClient(URL("https://api.github.com/graphql"), httpClient))
    }

    val gitHubClient: GitHubClient by lazy {
        GitHubClientImpl(graphQLClient, gitHubInfo, remoteBranchPrefix)
    }
}
