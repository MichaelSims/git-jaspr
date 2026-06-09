# ADR 0004: Worktree Lifecycle on GitClient

## Status

Accepted

## Context

jaspr uses git worktrees in a few places:

- Auto-merge spins up a detached worktree at `.git/jaspr/automerge-worktree`
  and drives a merge-queue-style rebase loop inside it.
- The sync-stack flow uses a detached worktree at `.git/jaspr/sync-worktree`
  to rebase non-checked-out branches without disturbing the user's HEAD.
- The divergence classifier (`jaspr pull`) maintains a probe worktree to
  cherry-pick candidate commits and inspect the resulting tree without
  touching the working copy.

Before this work, every worktree call site shelled out to `git worktree
add`/`remove` directly via `ProcessBuilder` instead of going through the
`GitClient` abstraction. That was the only remaining category of raw process
calls in `GitJaspr.kt` and `DivergenceClassifier.kt`. Moving these behind
`GitClient` is the goal of the broader refactor that this ADR sits inside.

The choice that needs capturing is *how* the worktree lifecycle should be
modeled on `GitClient`. Two shapes are plausible.

## Decision

`GitClient` exposes plain `addWorktree(path, ref?, detached)` and
`removeWorktree(path, force)` methods. Callers that need to operate inside
the worktree instantiate a worktree-bound `GitClient` themselves, typically
via `OptimizedCliGitClient(worktreeDir, ...)`. There is no
`withWorktree { client -> ... }` context manager on `GitClient`.

The auto-merge flow has already followed this pattern for some time:

```kotlin
gitClient.addWorktree(worktreeDir, ref = currentRef)
val worktreeGit = OptimizedCliGitClient(worktreeDir, config.remoteBranchPrefix)
// ...use worktreeGit...
gitClient.removeWorktree(worktreeDir, force = true)
```

JGit doesn't expose a linked-worktree creation API at any version. The
`*Worktree*` classes in the JGit jar are all about the in-memory working
tree (`NoWorkTreeException`, `ResolveMerger$WorkTreeUpdater`,
`SkipWorkTreeFilter`), not `git worktree add`. Reimplementing
`git worktree add` in pure Java would mean writing `.git/worktrees/<name>/`
ourselves and tracking git's internal worktree-registry format, which is
not a stable API. So `JGitClient.addWorktree` and `removeWorktree` throw
`UnsupportedOperationException`, and `OptimizedCliGitClient` overrides both
to route to `CliGitClient`. This is the same trade-off we made for
`mergeTreeWriteTree`: JGit has the building blocks (merge resolvers) but
not the porcelain shape we need, so we route through CLI.

## Alternatives considered

### `withWorktree(path, ref) { client -> ... }`

`GitClient` would expose a single block-shaped method that creates the
worktree, yields a fresh `GitClient` bound to it, and removes the worktree
on block exit. This is more idiomatic for "the client is bound to a
directory": worktree-bound work would be impossible to express without
materializing a worktree first.

We rejected it for this iteration because:

- Every `GitClient` implementation would have to know how to construct
  another `GitClient` bound to a different directory. That's a new internal
  factory responsibility; today `GitClient` implementations are
  self-contained.
- It would conflate two operations (worktree lifecycle + client
  construction) into one method, making it harder to express variations
  like "create the worktree but reuse an existing client wrapper" or
  "construct a worktree-bound client for an externally-managed worktree."
- The pattern jaspr already uses for auto-merge composes cleanly out of the
  pieces we're adding here; we don't pay a complexity tax to keep that
  pattern.

If we later find ourselves writing the same five lines of "add, construct,
use, remove" repeatedly, we can layer a `withWorktree` helper on top of the
existing primitives, either as a free function or as a `GitJaspr` private
helper. The primitives don't preclude that future.

### Pass an optional working-directory override to existing `GitClient` methods

Some methods could take an optional `dir: File?` parameter and run their
operation in that directory. Cleanly rejected: it would push worktree
awareness into every method's signature for the benefit of a small number
of call sites, and it leaks "where am I running git" into the abstraction
that's supposed to hide it.

## Consequences

- New callers can express worktree-bound work in plain code. The cost is
  one extra `OptimizedCliGitClient(worktreeDir, ...)` line per call site.
- `JGitClient` users will hit `UnsupportedOperationException` if they call
  these methods directly. Production wiring uses `OptimizedCliGitClient`,
  which routes through CLI. Tests that need to exercise worktree behavior
  must use the CLI path, mirroring `mergeTreeWriteTree`.
- The "explicit add + remove" shape leaves cleanup as a caller
  responsibility. Code that wants fire-and-forget removal (e.g., best-effort
  cleanup of stale worktrees from a crashed prior run) wraps the call in
  `try`/`catch`. This is consistent with how the rest of `GitClient`
  signals failures.
