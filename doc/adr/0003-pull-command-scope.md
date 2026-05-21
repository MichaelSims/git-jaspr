# ADR 0003: Pull Command Scope and Decision Tree

## Status

Accepted

## Context

`jaspr pull` is the command for incorporating remote-only commits from a named
stack into the local stack. The remote diverges from local in several ways: a
collaborator may have pushed new commits, someone may have rebased the stack onto
a newer target, individual commits may have been amended on one side or the
other, or any combination of these. Without `pull`, operators handle all of this
with raw `git fetch` plus cherry-pick or rebase.

The design problem is that "incorporate remote changes" is *unambiguous in a
small slice of real-world scenarios and genuinely ambiguous in most others*. A
pull that silently picks a default in ambiguous cases will sometimes destroy
work, regress stack bases, or produce surprising history. A pull that always
prompts for confirmation makes the simple case slow and erodes trust. So the
design questions are:

1. Which scenarios are unambiguous enough to act on automatically?
2. What does pull do in the rest?
3. How does pull avoid leaving the user in a conflicted or half-applied state?

This ADR commits to a narrow, fully transactional pull: it only acts in
scenarios where the desired outcome is unambiguous and achievable
non-interactively. Everything else is punted to the user with a message
pointing at `jaspr compare` and git.

## Decision

### Pull is fully transactional and never interactive

`jaspr pull` either completes its work without conflict, or refuses to start
with a clear message. There is no `jaspr pull --continue`, no `jaspr pull
--abort`, no mid-flight conflict-resolution UX. Pre-pull state is preserved if
work cannot be applied atomically.

This rule shapes everything else: pull only takes responsibility for cases
where correctness can be determined ahead of time, by inspecting the compare
classification and probing potential conflicts with `git merge-tree` before
touching the working tree.

### Pull is a thin layer over compare

`pull` consumes the `CompareRow` data produced by `compare`. All
classification (IDENTICAL, DIVERGED, REMOTE_ONLY, LOCAL_ONLY, reordered) is
done once during compare; pull decides what to do on top of that model. No
separate analysis path.

### Scenario taxonomy

Pull's behavior is selected via set-membership of commit-ids:

- `L` = set of commit-ids in the local stack
- `R` = set of commit-ids in the remote stack
- `LO` = `L - R` (local-only commit-ids)
- `RO` = `R - L` (remote-only commit-ids)

A shared row classifies as IDENTICAL only when both the patches AND the
commit messages (subject + body) match; patch-equivalent commits with
different messages classify as DIVERGED.

Decision tree:

Pull *punts* (refuses with a message pointing at `jaspr compare` and git)
when any of:

- A row classifies as DIVERGED (same commit-id, different content;
  see "Divergence handling" below)
- Both sides have unique commits (`LO` and `RO` are both non-empty)
- Stack bases are unrelated (neither is an ancestor of the other). This
  can only fire when the target ref has a non-linear history (merges or
  branching); on a strictly linear target, the two bases are always
  comparable.

If none of those hold, pull either:

- *no-ops with a note* when `LO` and `RO` are both empty but the shared
  commits are in different orders (pure reordering), or
- *auto-resolves* per the next section.

### Auto-resolve case: dispatch

Define:

- `local_base` = `merge-base(HEAD, target)`
- `remote_base` = `merge-base(remote-stack-ref, target)`
- **Remote has fresh state** = `RO` is non-empty, OR `remote_base` is
  strictly ahead of `local_base`, OR (the bases are equal AND at least
  one shared row has differing SHAs across the two sides).

The action depends on the base comparison and whether remote has any
fresh state to adopt:

```
if local_base strictly ahead of remote_base:
    # Local rebased onto a newer base; preserve local's view.
    if RO non-empty: cherry-pick RO onto local HEAD
    else:            no-op
elif remote has fresh state:
    # Adopt remote's shared portion; replay LO on top if any.
    if LO empty:     reset --hard to remote stack ref
    else:            cherry-pick LO onto remote tip
else:
    # Bases equal, RO empty, shared SHAs match.
    # Nothing fresh on either side; LO (if any) is unpushed local work.
    no-op
```

Notes on the action branches:

- `reset --hard` paths adopt remote's SHAs (and any base advancement)
  atomically. No new SHAs are fabricated; remote's commit dates are
  preserved.
- "Cherry-pick RO onto local HEAD" preserves a local rebase by applying
  remote's new commits on top of local's existing base.
- "Cherry-pick LO onto remote tip" matches `git pull --rebase` semantics
  for the case it covers: adopt remote's view of the shared portion and
  replay the loser's extras on top.
- The three no-op outcomes (spread across the first branch's `else` and
  the final `else`) cover: literal up-to-date (`LO` and `RO` both empty,
  all SHAs match), local strictly ahead via newer base (`RO` empty), and
  local has unpushed commits with remote unchanged (`LO` non-empty,
  `RO` empty, SHAs match).

The "cherry-pick LO onto remote tip" path is more destructive than the
other action paths: it rewrites local commit SHAs for the shared portion,
not just appends. Pre-pull HEAD remains in the reflog, so recovery is
`git reset --hard HEAD@{1}`. Whether to add a dedicated backup ref
(consistent with `--theirs` resolution which already uses backup refs) is open; current
intent is "reflog suffices" but pull's output for this path should be
explicit about what happened:

> Adopted remote's version of the shared portion of your stack and
> replayed N local commit(s) on top.

The phrasing is intentionally vague about whether the adopted shared
portion came from a rebase, an amendment, or both. Pull doesn't try to
diagnose which; it just reports what it did.

Output for the symmetric path (local base ahead, cherry-pick RO onto HEAD)
notes that local's base is ahead of remote's, so the user knows to push:

> Pulled N commit(s) onto your local stack. Your stack base is ahead of
> remote's; push to bring remote in sync.

The two `reset --hard` paths emit a one-line summary of what was adopted,
e.g.:

> Pulled N commit(s) from remote; your stack now matches remote.

### Conflict detection: probe before applying

The cherry-pick path uses `git merge-tree --write-tree` to probe each commit
in the queue before touching the working tree:

```
tree = HEAD-tree
for each remote-only commit Ci in queue:
    tree = git merge-tree --write-tree --merge-base=Ci^ tree Ci
    if non-zero exit: refuse with "cherry-pick of Ci would conflict"
```

Only after the entire queue probes cleanly does the real cherry-pick run.
This guarantees that by the time HEAD or the working tree changes, the
operation will complete. Pull never leaves the user in a conflict state.

`reset --hard` paths cannot conflict in the cherry-pick sense, but refuse
cleanly on a dirty working tree (matching `git pull --ff-only` semantics).

### Pre-pull preconditions

Pull refuses to start if any of:

- A cherry-pick is in progress (`.git/CHERRY_PICK_HEAD` exists)
- A rebase is in progress (`.git/rebase-merge` or `.git/rebase-apply` exists)
- A merge is in progress (`.git/MERGE_HEAD` exists)

Pull does *not* pre-check working-directory cleanliness. Git's own cherry-pick
and reset machinery refuse safely when files would be clobbered; jaspr
delegates that check.

### Idempotency

Both auto-resolve sub-paths are idempotent in the "no changes on either side"
sense:

- `reset --hard` path: local matches remote bit-for-bit afterward, so a second
  pull sees zero REMOTE_ONLY rows and exits no-op.
- Cherry-pick path: cherry-picked commits get new SHAs but identical patches.
  `DivergenceClassifier`'s patch-id fast path classifies them as IDENTICAL on
  the next run, so a second pull again sees zero REMOTE_ONLY rows.

This idempotency is checked by an explicit pull-then-pull-again integration
test.

### Divergence handling

When the compare contains any DIVERGED row (same commit-id, different
content), pull punts by default. Auto-merging two rewrites of the same
commit-id non-interactively requires policy the user must opt into.

The `--theirs` flag is the only opt-in resolution variant. When supplied,
pull takes the remote's content unconditionally for all DIVERGED rows by
cherry-picking remote's version of each diverged commit-id, then re-
dispatching through the auto-resolve table on the resulting (no-
divergence) shape. The flag is marked dangerous in help text. Before any
destructive change, pull writes a backup ref to
`refs/jaspr-backup/pre-pull-<unix-timestamp>` and surfaces the ref in
pull's output for one-command recovery (`git reset --hard <ref>`). If the
post-resolution dispatch would punt (e.g., mixed unique work still
present after divergence is resolved), or if any subsequent step fails,
pull rolls back to the backup ref and reports the rollback.

Symmetric variants (`--ours`, date-based picks, per-row interactive
selection) are deliberately out of scope.

## Alternatives Considered

### Cherry-pick-onto-HEAD as the universal pull

The simplest pull would always cherry-pick remote-only commits onto local
HEAD. Rejected: when the remote has rebased onto a newer target, this
regresses local to the older base, and pushing would then clobber the
coworker's rebase. Specifically:

- Local: `A B C D` on T1
- Remote: `A' B' C' D' E F` on T2 (coworker rebased + added)
- Cherry-pick result: `A B C D E F` on T1 (loses the T2 rebase silently)

The `reset --hard`-vs-cherry-pick decision based on base comparison was
introduced specifically to address this. Cherry-pick remains the right
primitive *when local's base is ahead*, but not as the default.

### Worktree probe for conflict detection

Using `git worktree add` and attempting the cherry-pick in the secondary
worktree was considered. Rejected: `git merge-tree --write-tree` does the same
job purely in-memory, faster and with no filesystem cleanup. Worktree probing
remains a fallback if `merge-tree` proves insufficient in practice, but it is
not the primary design.

### `--insert` flag for interleaved REMOTE_ONLY

A flag to rewrite local history and insert remote-only commits at their
remote-determined positions was considered. Rejected: too complex to implement
and explain, and the same compare shape arises from two opposite intents
(intentional local drop vs. not-yet-pulled). Falling back to git for these
cases is honest and simple.

### `jaspr pull --continue` / `--abort`

A jaspr-flavored continuation workflow over cherry-pick conflicts was
considered. Rejected: duplicates `git cherry-pick --continue` / `--abort`
(which the user already knows), adds state for jaspr to track, and conflicts
with the "fully transactional" contract. With `merge-tree` probing, conflicts
never happen at the jaspr layer, so continuation isn't needed.

### Partial work on mixed DIVERGED + REMOTE_ONLY

Letting pull do "the easy half" (cherry-pick the REMOTE_ONLY commits and
leave DIVERGED rows alone) was considered. Rejected: surprising UX (pull
"succeeded" but state isn't synced), and the user will need to handle the
DIVERGED rows themselves anyway, so the labor saving is illusory.

### Auto-merge divergence via commit-date "newer wins"

Auto-resolving DIVERGED rows by picking the side with the newer commit date
was considered and rejected. Commit dates are unreliable as a "who is newer"
signal in rebase-heavy workflows: rebasing without any content change updates
the committer date. "Remote-newer wins" is the only direction that actually
acts (local-newer-wins is a no-op), and it silently overwrites local edits.
The `--theirs` flag makes "take remote's content" an explicit user
choice with a backup-ref safety net rather than a default.

## Consequences

- `jaspr pull` becomes implementable as a thin layer over `compare`. The
  `CompareRow` model is its full input; no parallel analysis path.
- The diverged-stack message in `appendNamedStackInfo` can point at
  `jaspr pull` once it ships, in addition to `jaspr compare`.
- Backup refs under `refs/jaspr-backup/` enter the jaspr namespace,
  used by the `--theirs` opt-in to save recovery state before destructive
  divergence resolution.
- Conflict-handling code stays minimal: the probe-before-apply pattern means
  no `--continue` state machine to maintain.
- Shapes pull declines to handle (interleaved REMOTE_ONLY, mixed
  unique work, divergence with or without remote-only commits, reordering
  combined with new commits) accumulate as "use git directly" cases.
  Acceptable because the punt messages name what was seen and point at
  `jaspr compare` for inspection.
- Git 2.38+ becomes a baseline requirement (for `git merge-tree --write-tree`).

## Future Considerations

- A backup-ref retention policy under `refs/jaspr-backup/`.
- Pull suggesting `jaspr push` when local's base is ahead of remote's.
- Compressing identical prefix runs in pull's output for long stacks.
