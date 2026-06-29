# ADR 0006: Collapse GitClient Implementations

## Status

Accepted

## Context

`GitClient` had three concrete implementations:

- `JGitClient`: pure JGit, in-process.
- `CliGitClient`: pure git CLI, one process fork per call.
- `OptimizedCliGitClient`: the only implementation used in
  production. It delegated everything to `JGitClient` and overrode
  ~17 methods to route them to `CliGitClient` (transport, HEAD-moving
  ops, and operations JGit does buggily or not at all).

Two problems had accumulated:

- `JGitClient` implemented the full interface, so every method JGit
  could not do (cherry-pick, stash, worktree, merge-tree, ls-remote,
  patch-id) became an `UnsupportedOperationException` stub. Each new
  interface method forced another stub. A shared contract test had
  already started failing because it exercised `cherryPick` against
  the `JGitClient` backend, which throws.
- The `Optimized` qualifier had come to mean "the only one," which
  is misleading.

Before restructuring, we measured whether JGit earns its keep or
whether we could drop it for a single CLI-backed implementation.
On macOS, every `git` CLI invocation costs roughly 20 ms of
fork/exec/repo-discovery overhead, independent of the work done.
JGit pays that cost once per call (an in-process repo open) and
amortizes it across a walk. The read-heavy named-stack reachability
search (`getCommitIdsInRange`, run on every `status`) was 10x to 30x
faster via JGit, and the gap grows with the number of named stacks
because the CLI path forks once per stack. The fork floor is an
operating-system cost that the shipped native image cannot avoid.

## Decision

Keep JGit, but collapse the three classes into one production client.

- A single public `DefaultGitClient` delegates to `CliGitClient`
  (`GitClient by cli`) and overrides only the read-heavy and
  local-mutation methods to route them to a `JGitClient` accelerator.
- `CliGitClient` is the complete backend and the source of truth. It
  is the only implementation held to the full `GitClient` contract
  test, and it is what serves any method not explicitly accelerated.
- `JGitClient` does not implement `GitClient`; it is the accelerator
  exposing the read and local-mutation methods `DefaultGitClient`
  routes to it, so it carries no stubs.
- `DefaultGitClient` is the production client; `CliGitClient` and
  `JGitClient` are its helpers. They stay `public` (git-jaspr is a
  leaf module, where `internal` would be pure ceremony).

The routing is behavior-preserving: `DefaultGitClient` sends exactly
the methods JGit served before this change to JGit, and everything
else (transport, HEAD-moving ops, CLI-only ops) to the CLI.

Inverting the default to the CLI is the point. The CLI backend is
always correct, so a new interface method works through it
automatically and JGit opts in per method only where a measured
speedup justifies it. The stub treadmill is gone.

## Alternatives Considered

- Drop JGit entirely (single CLI-backed client). Rejected: it adds
  the ~20 ms fork cost to every read on macOS, turning the
  reachability search from tens of milliseconds into hundreds, on the
  exact platform the CLI routing exists to support. An "optimized" CLI
  that batches the reachability walk into fewer forks barely helped,
  because the cost is the fork, not the parsing.
- Rename `OptimizedCliGitClient` and leave the three-class structure.
  Rejected: it fixes only the misleading name. JGit would still
  implement the full interface, the stubs would remain, and the
  failing contract test would still need a separate fix.

## Consequences

- One public production client with a clear name. The set of
  JGit-accelerated methods is an explicit, greppable list of
  overrides rather than a delegation plus a parallel wall of stubs.
- No `UnsupportedOperationException` stubs, and the previously failing
  contract test passes because JGit is no longer held to the full
  contract.
- `JGitClient` still contains its (now unused in production) SSH
  transport code, so the build still pulls in the JGit SSH and jsch
  dependencies and the corresponding native-image reflection
  metadata. Removing that dead transport path and those dependencies
  is deferred to a follow-up so this change stays behavior-neutral.
- `JGitClient` is no longer a `GitClient`, so tests that cross-checked
  JGit against the CLI through a shared `(File) -> GitClient` factory
  now construct each backend directly.
