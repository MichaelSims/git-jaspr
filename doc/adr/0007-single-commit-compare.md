# ADR 0007: Single-Commit Compare

## Status

Accepted

Extends ADR 0002.

## Context

`jaspr compare` (ADR 0002) shows which commits differ between the local stack and the
remote named stack. A `~~` row says a commit's two versions have different content, but
there was no way to see how they differ.

A plain `git diff <local> <remote>` doesn't answer that question. After a rebase, the
two versions sit on different bases, so the diff includes everything that landed on the
target branch in between. For a commit that changed by one line, that one line can be
buried under hundreds of lines of unrelated upstream changes.

What the operator wants is the difference between the two versions *as patches*: what
changed in the commit itself, with changes caused only by rebasing removed. This is the
same notion of "content" that `DivergenceClassifier` already uses to decide `==` vs `~~`.

## Decision

### Command shape: overload `compare`

`jaspr compare` takes an optional positional argument:

```
jaspr compare                  # side-by-side stack view (unchanged)
jaspr compare <ref-or-index>   # diff one commit's two versions
```

The no-argument form has no positional arguments today, so this is backward compatible.
Overloading reads naturally ("compare the stacks" vs. "compare this commit") and keeps
the two views next to each other in help output.

The argument is resolved like this:

- An integer within the range of row indexes is treated as the `[N]` index from the
  side-by-side view. This requires running the full alignment so the numbering matches,
  which is cheap because classifier results are cached.
- Anything else is resolved as a ref to a commit and matched against either side of each
  row. A local SHA, a remote SHA copied from the right column, `HEAD~2`, or a branch name
  all work, and all of them select the same pair.

About 4% of 7-character abbreviated SHAs are all digits, so the two interpretations can
in principle collide. Git won't resolve an abbreviation shorter than four characters, so
an index can only shadow a SHA in a stack of 1000 or more rows. We accepted that rather
than requiring a prefix like `@3` on indexes.

Reordered commits pair through their shared index. A commit that exists on only one side
is an error that names the side it's on. An identical pair prints a one-line message
instead of an empty diff.

### Removing rebase changes: merge-tree, with range-diff as a fallback

The older version's patch is replayed onto the newer version's parent with an in-memory
three-way merge:

```
git merge-tree --write-tree --merge-base=<left>^ <right>^ <left>
```

The resulting tree is then diffed against the newer version (`git diff <tree> <right>`).
That produces an ordinary code diff of what changed in the commit, as if both versions
sat on the same base. The merge runs in memory, so it needs no worktree, lock, or index.

When that merge conflicts (the rebase touched the same lines as the commit), the output
says so and falls back to:

```
git range-diff --creation-factor=1000 <left>^..<left> <right>^..<right>
```

Range-diff compares the two patches as text, so it always works, but its output is a diff
of diffs (`+-` / `-+` prefixes), which is harder to read. That's why it's the fallback
and not the default.

The `--creation-factor` matters here. Range-diff decides on its own whether two commits
correspond, and with the default factor (60) it reports substantially different versions
as one removed commit and one added commit, with no diff at all. Each range here holds
exactly one commit and we already know they pair, so the factor is set high enough that
git always matches them.

### Commit message diff

The classifier counts a reworded message as divergence, so a reword-only `~~` row would
otherwise show an empty diff. When the messages differ, a message diff is shown first,
labeled `local/COMMIT_MESSAGE` and `remote/COMMIT_MESSAGE`. It's produced by `git diff
--no-index` on two temp files, so it gets the same rendering and color as the code diff.

### Direction: newer version on the right

The newer version (by commit date, using the same logic as the side-by-side view) is the
new side of the diff, so `+` lines are what the newer version has. When the local copy is
newer, the diff answers "what did I change." When the remote copy is newer, it answers
"what did my collaborator (or my other machine) change." It also agrees with the
side-by-side view, which marks the newer side with `*` and bold.

To keep the direction unambiguous:

- File headers use `local/` and `remote/` instead of git's `a/` and `b/` (via
  `--src-prefix` / `--dst-prefix`), so every file shows which side is which.
- A header line names both SHAs and marks the newer one: `remote 56552f6 → local 1a9c9c3
  (newer)`.
- When both versions were committed in the same second, the remote goes on the left,
  and the header says the dates are equal.
- `--reverse` swaps the sides.

### Rendering: hand off to git

All diff output comes from git, so the user's diff config (algorithm, context lines,
`color.diff`) applies. External diff drivers are disabled with `--no-ext-diff`, since
their output can't be combined with the rest. The combined output goes through git's
configured pager (`git var GIT_PAGER`, which honors `$GIT_PAGER`, `core.pager`, and
`$PAGER`), so diff pagers like `delta` work. Color is decided by `git config
--get-colorbool color.diff`.

## Alternatives Considered

### A separate command

Something like `jaspr compare-commit 3`. It would be more discoverable as its own entry
in the command list, but it splits two closely related views across two commands and
adds a name to remember. Rejected in favor of the overload.

### Range-diff as the default

Range-diff was built for this problem (comparing two versions of a patch series) and
never fails. Rejected as the default because the diff-of-diffs output is hard to read for
anything beyond a small change. Kept as the fallback.

### Cherry-pick in the probe worktree

`DivergenceClassifier` already cherry-picks one commit onto the other's parent in a
scratch worktree. Reusing that was the original plan. Rejected once we noticed
`mergeTreeWriteTree` already existed on `GitClient`: it computes the same tree without a
worktree, a file lock, or cleanup.

### Fixed remote-to-local direction

Always putting the remote on the left, so `+` lines are always "what the local version
has" (and what `jaspr push` would publish). This is simpler to explain, but when a
collaborator has amended a commit, the interesting change shows up as `-` lines.
Rejected in favor of newer-on-right with clear labels and `--reverse`.

## Consequences

- `GitClient` gains `diff`, `diffText`, and `rangeDiff`, implemented only in
  `CliGitClient`. `DefaultGitClient` delegates them to the CLI backend.
- `getCompareString`'s stack loading moved into a shared `loadCompareStacks`, so both
  forms of `compare` see the same stacks, including the nav-session and already-merged
  handling.
- The direction depends on commit date, and a rebase bumps the commit date without
  changing content. If a collaborator amends a commit and the operator then rebases a
  stale local copy, the stale copy looks newer and the diff reads backwards. The
  side-by-side view's `*` marker has the same weakness. The header labels and `--reverse` make
  this recoverable. The proper fix is tracked in #347, which proposes deciding "newer"
  from each commit's version history instead of the clock. That would improve both views.
