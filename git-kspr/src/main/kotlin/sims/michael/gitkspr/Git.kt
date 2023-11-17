package sims.michael.gitkspr

import java.util.*

fun extractGitHubInfoFromUri(uri: String): GitHubInfo? {
    // We only care about GitHub-supported URIs, which vastly simplifies things. See:
    // https://docs.github.com/en/get-started/getting-started-with-git/about-remote-repositories
    val regex = if (uri.startsWith("git@")) {
        "^git@([a-zA-Z0-9._-]+):(\\w+)/([\\w-]+)(?:\\.git)?$".toRegex() // SSH
    } else {
        "^https://([a-zA-Z0-9._-]+)/(\\w+)/([\\w-]+)(?:\\.git)?$".toRegex() // HTTP
    }

    return regex.matchEntire(uri)?.let { matchResult ->
        val (host, owner, name) = matchResult.destructured
        GitHubInfo(host, owner, name)
    }
}

fun generateUuid(length: Int = 8): String {
    val lengthConstraint = 8..20
    require(lengthConstraint.contains(length)) { "Length must be within $lengthConstraint " }
    val fullUuid = UUID.randomUUID().toString()
    // Version 4 UUID... extract the time-low and node sections, skipping the portions that contain the version and
    // variant
    val (eight, twelve) = requireNotNull(Regex("(.{8})(?:-.{4}){3}-(.{12})").matchEntire(fullUuid)).destructured
    return (eight + twelve).take(length)
}
