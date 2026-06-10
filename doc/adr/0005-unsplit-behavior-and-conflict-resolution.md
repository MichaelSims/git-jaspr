# ADR 0005: Unsplit Behavior and Conflict Resolution

## Status

Accepted

## Context

`jaspr split` resets HEAD past the current commit (mixed reset), leaving its
changes in the working tree, and records the pre-split SHA in
`.git/jaspr/split-state.json`. `jaspr unsplit` is the inverse: it consumes
that state to restore the original commit, preserving its message and
`commit-id:` trailer.

The original unsplit (ADR-0001) only implements one pattern: **absorb**.
It soft-resets to the pre-split SHA, stages the working tree, and amends,
folding any work done since the split back into the original commit.

In practice the operator's most common reason for splitting is not "absorb
edits into the original" but **extract a precursor commit**: peel a
refactor, rename, or cleanup out of a commit into its own commit *before*
the original. The desired end state is `[N1=precursor, N2=original]`,
where `N2` retains the original's message and `commit-id:` trailer so
jaspr's stack tracking still works.

Today the operator does this manually after `jaspr split`:

1. `git add -p` (or `git restore` + `git add -A`) to build N1.
2. `git commit` to land N1.
3. `git cherry-pick <unsplitSha>` to put the original on top of N1.

`jaspr unsplit` should support this pattern directly. The design questions
are:

1. How does `unsplit` know whether the operator wants absorb (fold
   everything back) or extract (cherry-pick original on top of new work)?
2. How are conflicts in the cherry-pick path handled, given that
   `jaspr pull --theirs` (ADR-0003) establishes a precedent of non-
   interactive resolution with a backup ref for recovery?
3. What happens at the boundaries: identical-result cherry-picks,
   in-progress cherry-picks left by failed unsplits, and the existing
   `jaspr nav cancel` escape hatch?

## Decision

### Auto-detect absorb vs cherry-pick

`unsplit` chooses its path from two pieces of state: whether HEAD is
still at the split point (i.e., the parent of `unsplitSha`), and whether
the index plus working tree are clean. The absorb path is correct only
when HEAD has not moved since the split, because its soft reset to
`unsplitSha` would otherwise jump past intermediate commits and destroy
them. Cherry-pick is the path everywhere else.

Combined rule: **absorb only when HEAD is at the split point *and* there
are uncommitted changes to fold in. Cherry-pick in every other case.**

| HEAD position | Tree state | Path | Result |
|---|---|---|---|
| At split point | Dirty | Absorb | Original recreated with edits folded in. |
| At split point | Clean | Cherry-pick | Original recreated; see "Identical-result optimization". |
| Moved (one or more intermediate commits) | Clean | Cherry-pick | `[N1, ..., original-on-top]`. |
| Moved | Dirty | Cherry-pick | Same as above; the dirty working tree participates in cherry-pick's 3-way merge. |

The fourth case is the one where a tree-state-only discriminator would
silently destroy intermediate commits. Routing it to cherry-pick instead
of absorb matches the operator's manual workflow (`add -p` + commit + run
unsplit without first stashing the remainder), and modern cherry-pick's
3-way merge correctly resolves the overlap between the dirty working
tree and the patch when the dirty content is what the patch would write
anyway.

### Cherry-pick path: probe with merge-tree, then act

The cherry-pick path probes for conflicts with `git merge-tree
--write-tree` before touching the working tree, then chooses how to
actually run the cherry-pick based on what the probe found. This mirrors
the approach in ADR-0003's pull command and keeps the bail path's side
effects to a minimum (working tree untouched if we know we'd fail).

1. Save a backup ref at `refs/jaspr-backup/pre-unsplit-<unix-timestamp>`
   pointing at current HEAD. Matches the naming used by
   `resolveTheirsAndPull` (ADR-0003).
2. Probe in-memory:
   ```
   git merge-tree --write-tree --merge-base=<unsplitSha>^ HEAD <unsplitSha>
   ```
   The output names any conflicting paths and classifies their conflict
   types via exit code and the diagnostic section of merge-tree's
   output.
3. Branch on probe outcome:
   - **No conflicts** → `git cherry-pick <unsplitSha>` (no strategy
     option needed). Clear split state. Quiet success.
   - **Only content-level conflicts** → `git cherry-pick -X theirs
     <unsplitSha>`. The recursive/ort strategy resolves each conflicting
     hunk in favor of `unsplitSha`. Clear split state. Emit the
     auto-resolved warning citing the file count from the probe.
   - **Conflicts `-X theirs` cannot resolve** (modify/delete,
     rename/rename, etc.) → refuse without running cherry-pick at all.
     Working tree and HEAD are unchanged. Emit the bail message
     pointing at manual resolution or `jaspr nav cancel`.
4. The final commit's message and `commit-id:` trailer are preserved
   automatically: `git cherry-pick` reuses the source commit's message
   by default, so no `-C` flag is needed in the success paths.

### Why "theirs" on content conflicts

When the operator extracts a precursor commit, the original is the "real"
change they wanted to land; the precursor is preparation. If the two
genuinely conflict at the same lines, the original's content is what
should appear in the final tree. This matches the operator's manual
workflow today (running `git cherry-pick` and resolving conflicts by
preferring the cherry-picked side) and aligns with the precedent in
ADR-0003's `--theirs` resolution mode.

Git's rename detection covers the common rename precursor case without
needing the `--theirs` resolution at all: if N1 renames `foo` → `bar` and
the original modifies `foo`, the 3-way merge tracks the rename and
applies the modification to `bar` cleanly. `--theirs` only kicks in when
the same lines were touched on both sides.

### Identical-result optimization

If the prospective cherry-pick result would have the same tree, parent,
and commit message as `unsplitSha` itself (e.g., split immediately
followed by unsplit with no work done in between), `unsplit` sets HEAD to
`unsplitSha` directly rather than creating a new commit with a fresh SHA.

This avoids manufacturing a new SHA when the prior SHA is reachable and
equivalent. Scoped to `unsplit` only; other nav operations don't
construct commits whose tree+parent+message could collide with a known
prior SHA.

### Operator-facing messages

The cherry-pick path produces one of three message shapes. All name the
restored commit's short SHA and subject; the literal text is
implementation detail and not committed by this ADR.

- **Clean success** (no conflicts encountered): a confirmation line.
- **Auto-resolved** (one or more files had content conflicts resolved
  via "theirs"): an emphatic warning that includes the count of files
  affected, the backup-ref name, the command to view the auto-resolved
  diff against the backup, and the command to hard-reset to the backup
  if the result is wrong.
- **Unresolvable** (a conflict "theirs" cannot handle, e.g. modify/
  delete or rename/rename, detected by the merge-tree probe): an error
  naming what merge-tree found and noting that the working tree was
  not touched. Suggests resolving manually with raw git commands, and
  points at `jaspr nav cancel` as the ultimate bailout.

The absorb path's message is unchanged from the existing implementation.

### Teach `jaspr nav cancel` to handle in-progress cherry-picks

`jaspr nav cancel` is the framed "hard escape" from any navigation state
(GitJaspr.kt: `cancelNavSession`). The merge-tree probe means `unsplit`
itself never leaves a cherry-pick in progress, but the operator can land
in that state by running `git cherry-pick <unsplitSha>` manually after a
split, by `unsplit`'s success path being interrupted between the cherry-
pick and the split-state clear, or by any other workflow that leaves
`.git/CHERRY_PICK_HEAD` present.

When `cancel` runs and `.git/CHERRY_PICK_HEAD` exists, it aborts the
cherry-pick before performing its normal restore. This is a small
addition to `cancel`'s existing "clear split state if present" behavior
and matches the role it already plays.

## Alternatives Considered

### Explicit flag (`--absorb` / `--on-top`)

Requiring the operator to choose explicitly between absorb and cherry-
pick on every invocation. Rejected: the choice is determinable from
HEAD position and tree state in every case, and a mandatory flag adds
friction to the common path.

### Separate commands (`jaspr unsplit` vs. `jaspr resume`)

Splitting the two intents into named commands. Rejected on the same
grounds: in the common cases the tree state already disambiguates, so the
operator gains no information from a second command name. The naming
overhead also lands on muscle memory for an operation done frequently.

### `git cherry-pick -X theirs` unconditionally

Always passing the strategy flag and letting git resolve everything in
one call. Rejected: gives no way to report N files affected to the
operator and no hook to distinguish "no conflicts at all" from
"auto-resolved silently." The merge-tree probe preserves both signals
at the cost of one read-only call before the cherry-pick.

### Run cherry-pick first, abort on unresolvable

`git cherry-pick --no-commit`, then inspect unmerged paths, attempt
`git checkout --theirs` on each, then commit or abort. Rejected:
when conflicts are unresolvable, this approach leaves the cherry-pick
in progress and the working tree partially modified, forcing the bail
message to spell out the abort + reset sequence. Probing first with
`merge-tree` means the working tree is untouched in the unresolvable
case, simplifying both the messaging and the recovery story.

### Auto-resolve via `-X ours`

Picking the operator's side (N1) on conflict. Rejected: contrary to the
operator's stated intent. The original commit is what they meant to land;
N1 is scaffolding around it.

### Pruning backup refs automatically

Cleaning up `refs/jaspr-backup/pre-unsplit-<ts>` after some period or on
next `unsplit`. Rejected for now (matches pull's stance in ADR-0003):
backups are cheap, and an operator finding an old backup and reading the
timestamp is less surprising than a backup having vanished when they
went to recover. Retention policy is open across all of
`refs/jaspr-backup/` and should be revisited as one decision.

## Consequences

- `unsplit`'s contract widens: it now supports both absorb and extract
  patterns, with the path chosen non-interactively.
- The original closed work item for `jaspr unsplit` is superseded by
  this ADR for the conflict-handling and cherry-pick-path behavior.
  Either reopen it with a revised description or file a follow-up.
- `refs/jaspr-backup/` accrues `pre-unsplit-*` entries alongside
  `pre-pull-*`. No retention policy in either case.
- `jaspr nav cancel` gains a small in-progress-cherry-pick cleanup step;
  its visible behavior is unchanged for callers who don't have a
  cherry-pick in flight.
- Documentation and help text for `jaspr unsplit` need to describe the
  auto-detect rule and the conflict-resolution behavior so operators
  understand the "theirs" choice.
- Tests need to cover: absorb path unchanged, cherry-pick clean path
  with HEAD moved and with HEAD at split point, cherry-pick auto-
  resolved path (file count surfaced), cherry-pick unresolvable path,
  cherry-pick with dirty working tree and intermediate commits (the
  4th-cell case), identical-result optimization, and `nav cancel`
  cleaning up an in-progress cherry-pick.

## Future Considerations

- Retention policy for `refs/jaspr-backup/` ref namespace.
- Surfacing the auto-resolved file list in the warning rather than just
  the count.
