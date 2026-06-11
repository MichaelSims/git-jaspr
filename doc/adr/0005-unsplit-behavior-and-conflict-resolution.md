# ADR 0005: Unsplit Behavior and Conflict Resolution

## Status

Accepted

## Context

`jaspr split` performs a mixed reset to the parent of the current
commit, leaving the commit's changes in the working tree, and records
the pre-split SHA in `.git/jaspr/split-state.json`. `jaspr unsplit` is
the inverse: it consumes that state to restore the original commit,
preserving its message and `commit-id:` trailer.

The original unsplit implementation supports one workflow: the operator
splits a commit to "open it up," edits the working tree (often with help
from the IDE: rename refactor, inline, extract, reformatting), then
unsplits to "close it back up" with the edits folded in. This is an
amend done out in the open.

In practice operators also use split to extract a precursor commit out
of a larger one. The intuition: peel a refactor, rename, or cleanup off
of a feature commit so it can land first on its own, with the original
following. The desired end state is `[precursor, original-on-top]`,
where the on-top commit retains the original's message and `commit-id:`
trailer so jaspr's stack tracking still works.

Today the operator does the precursor flow manually after `jaspr split`:

1. `git add -p` (or `git restore` + `git add -A`) to build the
   precursor.
2. `git commit` to land it.
3. `git cherry-pick <unsplitSha>` to put the original on top.

`jaspr unsplit` should handle both workflows. The design questions are:

1. How does `unsplit` know which workflow the operator is in?
2. How are conflicts in the precursor flow handled, given that
   `jaspr pull --theirs` (ADR-0003) establishes a precedent of
   non-interactive resolution with a backup ref for recovery?
3. How does the operator recover if the result is not what they wanted?

## Decision

### Two named modes, auto-detected from HEAD position

`unsplit` has two modes, named for the dominant mental model of each:

- **Fold mode**: take whatever is in the working tree and fold it into
  a new commit with the original's message and metadata. The working
  tree is the truth. Used when the operator has been editing the
  working tree since the split and now wants to close the commit back
  up.
- **Replay mode**: replay the original commit on top of whatever HEAD
  now points at. The original commit is the truth. Used when the
  operator has committed something since the split (a precursor) and
  now wants the original to land on top.

The mode is decided from one piece of state: whether HEAD has moved
since the split.

| HEAD position                       | Mode   |
|-------------------------------------|--------|
| At the split's parent               | Fold   |
| Anywhere else (precursor committed) | Replay |

Working-tree state does not participate in the decision. In fold mode
the working tree *is* the answer. In replay mode the working tree is
leftover snapshot content from the split, and is set aside (see
"Disaster recovery").

### Why working-tree state is not part of the decision

The working tree is a set of files on disk; it does not remember which
commit produced it. `git diff` and `git status` compute their output at
display time, as the difference between the working tree's files and
whatever HEAD currently points at. After `jaspr split`, the working
tree's content is the original commit's tree, and HEAD is the
original's parent, so the implied diff is exactly the original's diff.
That is the diff a fold would commit.

If the operator commits a precursor, the working tree's content does
not change (commit operates on the index, not the working tree), but
HEAD has moved. The implied diff is now (original's tree) minus
(precursor's tree), which is a gap, not the original's diff. Folding
that gap produces a tree that depends on the precursor in ways the
operator did not intend: when the precursor renamed or refactored
content that the original later removed, the fold leaves the renamed
line in place rather than deleting it.

The right operation on top of a precursor is cherry-pick. Cherry-pick
applies the original's diff against its true parent (a 3-way merge
with the original's parent as the merge base) onto the precursor's
tree. The working tree's snapshot does not enter the calculation.

### Fold mode mechanics

1. Save current HEAD as
   `refs/jaspr-backup/pre-unsplit-<unix-timestamp>` so the operator can
   roll back. Naming matches the precedent in ADR-0003.
2. `git add --all` to stage the working tree (matches the operator's
   manual amend flow).
3. Commit using the original's message (including `commit-id:` trailer)
   and the original's author identity. Committer is the current
   operator.
4. Clear the split state.

If the working tree matches HEAD (nothing to fold), the fold would
produce a commit with the original's message but without the original's
content, because HEAD's tree is the original's parent, not the
original. This edge case is treated as an identity restore: set HEAD to
the original's SHA directly without creating a new commit. This is the
only situation in which `unsplit` reuses the original SHA rather than
producing a fresh one.

### Replay mode mechanics

1. Save current HEAD as
   `refs/jaspr-backup/pre-unsplit-<unix-timestamp>`.
2. If the working tree is dirty,
   `git stash push --include-untracked -m "jaspr unsplit pre-state <ts>"`.
   The stash exists for the operator's recovery and is not auto-popped.
3. `git cherry-pick -X theirs <unsplitSha>` onto current HEAD. The
   `-X theirs` strategy resolves any content conflict in favor of the
   original commit. The resulting commit reuses the original's message
   and `commit-id:` trailer automatically; cherry-pick preserves both
   by default.
4. Clear the split state.

Path-level conflicts (modify/delete, rename/rename, type-change) can
still surface even with `-X theirs`, because the strategy resolves
*content* conflicts, not structural ones. When the cherry-pick fails
in this way, leave it in progress and surface the standard
"cherry-pick is in conflict" message. `jaspr nav cancel` aborts the
in-progress cherry-pick and rewinds, restoring the pre-unsplit state.

### Why "theirs" in replay mode

When the operator extracts a precursor, the original commit is the one
they meant to land. The precursor is preparation. If the two genuinely
conflict at the same lines, the original's content is what should
appear in the final tree. This matches the operator's manual workflow
(running `git cherry-pick` and preferring the cherry-picked side) and
aligns with ADR-0003's `--theirs` mode.

Git's rename detection covers the common rename-precursor case without
`-X theirs` doing anything: if the precursor renames `foo` to `bar` and
the original modifies `foo`, the 3-way merge tracks the rename and
applies the modification to `bar`. `-X theirs` only activates when the
same lines were touched on both sides.

### Disaster recovery contract

Every `unsplit` invocation, in either mode, leaves a backup ref at
`refs/jaspr-backup/pre-unsplit-<unix-timestamp>` pointing at the
pre-unsplit HEAD. Operators recover via:

- **Fold mode rollback**:
  `git reset --mixed refs/jaspr-backup/pre-unsplit-<ts>`. HEAD rewinds,
  the new commit's tree drops back into the working tree (matching what
  was there before the fold), and the operator is back to the
  pre-unsplit state.
- **Replay mode rollback**:
  `git reset --hard refs/jaspr-backup/pre-unsplit-<ts>` to rewind HEAD
  and the working tree. If a stash entry was created (the working tree
  was dirty before replay), follow with `git stash pop` to restore the
  dirty working-tree content. The stash is identified by its message
  prefix `jaspr unsplit pre-state`.

Recovery is operator-initiated. `unsplit` never auto-pops the stash,
never auto-rewinds, and never reads the backup ref programmatically.
The point of the contract is that the operator looking at the result
and saying "that's not what I wanted" has a documented one-or-two
command undo.

`unsplit` does not garbage-collect old backup refs. They accrue
alongside `pre-pull-*` from ADR-0003. Retention is left to the operator
and is open across the `refs/jaspr-backup/` namespace as a whole.

### Operator-facing messages

Message shapes, all naming the restored commit's short SHA and subject.
Literal text is implementation detail.

- **Fold mode success**: a confirmation line stating the original was
  folded back from the working tree, on top of the original's parent.
- **Replay mode clean success**: a confirmation line stating the
  original was replayed on top of HEAD.
- **Replay mode with auto-resolved conflicts**: a multi-line warning
  that includes the count and list of files whose content conflicts
  were resolved by taking the original's content, the backup-ref name,
  and a pointer to `git stash list` if a stash was created.
- **Replay mode with path-level conflicts**: the standard "cherry-pick
  is in progress, resolve and continue or run `jaspr nav cancel`"
  message. This is the only outcome that leaves an in-progress
  cherry-pick.

### `jaspr nav cancel` aborts in-progress cherry-picks

`jaspr nav cancel` is the framed "hard escape" from any navigation
state. When `cancel` runs and `.git/CHERRY_PICK_HEAD` exists (whether
left by a failed `unsplit`, by a manual `git cherry-pick`, or by any
other workflow), it runs `git cherry-pick --abort` before performing
its normal restore. This is a small addition to `cancel`'s existing
behavior and complements `unsplit`'s replay-mode path-conflict outcome.

`cancel` is destructive by contract: in addition to aborting any
in-progress cherry-pick, it always hard-resets the working tree to
the original branch tip and removes untracked files. Uncommitted
changes (staged or unstaged) and untracked files are discarded.
Operators who want to preserve such state must stash with
`git stash --include-untracked` before running cancel. This matches
the verb's intent and avoids the operator-corrupted-state cases where
cancel would otherwise refuse with a checkout conflict.

## Alternatives Considered

### Explicit flags (`--fold` / `--replay`)

Requiring the operator to choose explicitly on every invocation.
Rejected: HEAD position already disambiguates without ambiguity, and a
mandatory flag adds friction to the common path. The two modes are
distinguishable not by intent but by objective git state.

### Working-tree state as the discriminator

Using "is the working tree dirty?" or "is anything staged?" to choose
between fold and replay. Rejected: working-tree state is incidental to
the decision. The operator who committed a precursor often still has
working-tree content (the post-stage remainder from `git add -p`), and
they want replay, not fold. A working-tree discriminator would route
them to fold and produce the wrong tree.

### Merge-tree probe before cherry-pick

Probing for conflicts with `git merge-tree --write-tree` before running
cherry-pick, then choosing the strategy or refusing based on the probe.
Rejected: with `-X theirs` as the always-applied strategy in replay
mode, the probe's only remaining purpose is to count conflicts for the
operator message. That can be done by parsing cherry-pick's output
post-hoc, without the probe's extra round trip.

### Auto-resolve via `-X ours`

Picking the operator's side (the precursor) on conflict. Rejected:
contrary to the operator's stated intent. The original commit is what
they meant to land; the precursor is scaffolding around it.

### Auto-pop the stash in replay mode

After a successful replay, automatically `git stash pop` the captured
pre-state. Rejected: the working tree after a successful replay matches
the original commit's tree, which is already what the operator wants.
Auto-popping the stash would reintroduce the pre-replay dirty content
on top of that tree, producing a workspace that holds two overlapping
snapshots of the original's content with no clear merge. Leaving the
stash for manual recovery preserves the operator's control.

### Pruning backup refs automatically

Cleaning up `refs/jaspr-backup/pre-unsplit-<ts>` after some period or
on next `unsplit`. Rejected for now (matches the stance in ADR-0003):
backups are cheap, and an operator finding an old backup is less
surprising than a backup having vanished. Retention is open across the
`refs/jaspr-backup/` namespace as a whole.

## Consequences

- `unsplit`'s contract widens: it now supports both the review-and-
  amend workflow (fold) and the extract-a-precursor workflow (replay),
  with the mode chosen non-interactively from HEAD position.
- `refs/jaspr-backup/` accrues `pre-unsplit-*` entries alongside
  `pre-pull-*`. No retention policy in either case.
- Replay mode adds a stash entry when the working tree is dirty. The
  entry uses a recognizable message prefix so the operator can identify
  it in `git stash list`.
- `jaspr nav cancel` gains a small in-progress-cherry-pick cleanup
  step; its visible behavior is unchanged for callers who don't have a
  cherry-pick in flight.
- Documentation and help text for `jaspr unsplit` should describe the
  fold/replay vocabulary, the auto-detect rule, the conflict-resolution
  behavior, and the disaster-recovery contract.
- Tests need to cover: fold mode with dirty working tree, fold mode
  with clean working tree (identity restore), replay mode clean
  cherry-pick, replay mode with auto-resolved content conflicts, replay
  mode with path-level conflicts (cherry-pick left in progress), replay
  mode with dirty working tree (stash captured), and `nav cancel`
  aborting an in-progress cherry-pick.

## Future Considerations

- Retention policy for the `refs/jaspr-backup/` ref namespace.
- Operator-facing help that lists active backup refs and stashes from
  prior unsplits.
