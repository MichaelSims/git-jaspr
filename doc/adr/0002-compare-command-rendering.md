# ADR 0002: Compare-Command Rendering and the Status/Compare Split

## Status

Accepted

## Context

`jaspr status` is the operator's main tool for understanding the relationship between
their local stack and the remote. As the project gains a `jaspr pull` command, status
will also be the basis for deciding when and how to pull. The current single-screen
render tries to do too much and falls down in two specific ways:

1. **Remote-only commits lack positional context.** When the remote stack has commits
   the local stack doesn't, those commits get listed in a footer section below the
   per-commit table, in isolation, with no indication of where in the stack they would
   slot. An operator can't tell whether a remote-only commit is "new on top," "inserted
   in the middle," or "alternative history at some fork point." For `jaspr pull` to be
   safe to invoke, the operator needs to see where new content lands relative to their
   own commits, not just that it exists.

2. **Per-commit arrow indicators are direction-ambiguous.** The ⬆️/⬇️/⏫/⏬/🔀
   indicators recently added to the per-commit row convey direction (local-newer vs
   remote-newer) and divergence, but it isn't visually obvious which side of the pair
   each arrow describes. Multiple operators have read the arrow as "the state of the
   remote commit" rather than "the state of the local commit relative to the remote."

The single-arrow `❗` was unambiguous when its meaning was just "commits differ."
Adding direction reintroduced an implicit "from whose perspective?" that the glyph
alone cannot answer.

This ADR records the design we landed on after a deliberation pass that explored
roughly half a dozen alternative renderings and highlight modes.

## Decision

### Split status into two presentations

Keep `jaspr status` focused on the state of the **local** stack: what the operator
owns and is responsible for pushing. Introduce a sibling command `jaspr compare`
focused on the relationship between the local stack and the remote named stack.

Each command describes one perspective, so the arrow-direction question evaporates:
`compare` always shows both sides explicitly in a side-by-side layout, and any
direction-laden visual cue (e.g., "the newer side") refers to a specific column the
reader can see.

### Compare's row layout

Each pair of commits renders as a single row spanning two columns:

```
LOCAL                              REMOTE (<remote>/jaspr/.../<stack>)

[1] abcd123 dev-00             ==  [1] wxyz789 dev-00
[2] abcd123 dev-01-amended     ~~  [2] wxyz789 dev-01
[3] abcd123 dev-02                                        [local-only]
                                   [4] wxyz789 dev-other  [remote-only]
```

- **Shared row index** `[N]` in brackets on both sides. Per-invocation; resets between
  runs. Lets the reader cross-reference rows when alignment isn't row-for-row.
- **Marker column** is one of three values:
    - `==` content-identical (rebased, but same patch)
    - `~~` same commit-id, content diverged
    - blank one-sided row (local-only or remote-only)

No direction arrows in the marker. Direction information, when needed, comes from
per-cell highlighting (below).

### Highlighting on diverged rows

When a row's relation is `~~`, emphasize the newer side with three overlapping cues:

1. **Asterisk** in a dedicated column before the SHA on the newer side. Survives
   copy-paste; works without ANSI styling.
2. **Bold** the newer side's text. Visible in normal terminal rendering.
3. **Dim** the older side's text. Asymmetric weight makes the contrast immediate.

All three cues fire together on `~~` rows; the redundancy is intentional. Asterisks
survive log files and screenshots; bold and dim survive copy-paste-as-html in some
viewers but not others; both together gracefully degrade depending on the consumer.

"Newer" is determined by commit date. If both commits share the same wall-clock
second (rare; the existing `DIVERGENT` case), render `~~` with no highlight and let
the marker carry the meaning.

### Alignment: LCS with a cross-reference post-pass

Align the two commit-id sequences using a longest-common-subsequence walk:

- Find the LCS of the two commit-id lists.
- Walk both lists in parallel, treating the LCS as the alignment spine.
- When both heads match the next LCS element, emit a paired row.
- When local's head isn't on the spine, emit a local-only row.
- When remote's head isn't on the spine, emit a remote-only row.

For a verbal description suitable for code comments: *"Any items from the list, in
their original order, not necessarily adjacent in the original."* This line should
appear verbatim as a comment on the alignment function.

After the LCS walk, run a cross-reference post-pass: for every one-sided row, check
whether its commit-id appears on the other side. If yes, re-label both occurrences as
`[reordered]` and give them a shared row index. The shared index lets the reader
connect the two appearances by eye without forcing a special-case render path for
reordered stacks.

### Compare's exit code

Always exit 0, regardless of drift. Future `jaspr pull` does its own analysis to
decide what's actionable. `compare` is a viewer, not a gate.

### Status's drift summary

`status` keeps its existing PR-readiness output for the local stack and gains a
one-line drift summary that replaces the existing "Remote stack has N commits..."
footer:

```
! Remote stack has drifted: 1 remote-only commit, 1 local commit not yet on remote.
  Run `jaspr compare` for details.
```

The per-commit AHEAD/BEHIND/DIVERGENT indicators leave `status` entirely. They live
nowhere; their information is recovered by `compare`.

### `jaspr graph`

A small additional convenience command, separate from `compare`: `jaspr graph` execs
`git log --graph --oneline --decorate --abbrev-commit` with jaspr's relevant refs
pre-filled (`HEAD`, the remote named-stack ref, the remote target branch). Lets the
operator see the raw git topology when they want it, without typing out jaspr's ref
encoding by hand.

`graph` is deliberately *not* part of `compare`. `compare` is jaspr-flavored
(indexes, content classification, no PR data, no target branch). `graph` is raw git
output with refs pre-filled. Two different jobs.

## Alternatives Considered

### Highlighting modes

Six modes were prototyped via `StatusPreviewTest`'s `compare highlight - *` previews:

| Mode | What it does | Why not |
|------|--------------|---------|
| arrows | `<~~` / `~~>` in marker column | Adds direction back into a symbol; less robust than the side-by-side column layout, which already shows direction visually |
| asterisk only | Asterisk on the newer SHA | Works, but undersells the signal compared to combined highlight + dim |
| dim only | Dim the older side | Subtle but possibly missed; loses signal in copy-paste |
| bold only | Bold the newer side | Visible, but asymmetric weight without the dim contrast is less clear |
| asterisk + dim | Both | Strong, but missed the "newer side stays in normal weight" emphasis that bold adds |
| **asterisk + bold + dim** | **All three** | **Chosen.** Triple-encoding lets each cue survive a different rendering context (plain text, copy-paste, ANSI terminal). |

Bright foreground or background color on the newer side was considered but felt
heavier than the signal warranted. A "newer-by-a-second" commit isn't an alarm; it's
a hint.

### Alternative layouts

- **Paired columns in `status`** (one rendering for both perspectives): solves
  direction-ambiguity but doesn't solve positional context. Rejected.
- **Inline `remote:` annotation under each local row**: solves both legibility
  problems within one command. Rejected because it doubles line count in the common
  case, gets noisy when nothing's drifted, and conflates two perspectives.
- **3-way DAG including target**: the "pie in the sky" rendering with local, remote,
  and target interleaved. Rejected as a primary layout: degrades quickly past about
  five commits, hard to keep scannable with forks. The `jaspr graph` convenience
  command captures this use case at a fraction of the cost.

### Pure-reorder special case

A dedicated render path for pure reorders (both stacks have the same set of
commit-ids, just in different order) was prototyped. The motivating output:

```
[1] dev-00 (A)     ==  [1] dev-00 (A)     [remote pos 1]
[2] dev-02 (B)     ==  [2] dev-02 (B)     [remote pos 3]
[3] dev-03 (C)     ==  [3] dev-03 (C)     [remote pos 2]
```

Three rows, no doubled commits. The LCS rendering on the same input produces four
rows with `dev-02` (B) appearing twice, once as `[local-only]` and once as
`[remote-only]`, which is mechanically correct but reads as if B is missing on both
sides.

Rejected anyway. Pure reorders without amendments are rare in real workflows; the
cross-reference post-pass converts the LCS-doubled rows into legible `[reordered]`
annotations with shared indexes, which is good enough; and a third rendering mode
means three code paths to maintain, three to test, and three layouts an operator
has to recognize.

### Always exit non-zero on drift

Considered for script-friendliness with future `jaspr pull`. Rejected: `pull` should
do its own analysis. Exit codes that encode "drift detected" make `compare` brittle
as a script tool and surprising as a viewer.

## Consequences

- `StatusBits.Status` shrinks back to its pre-divergence set (SUCCESS, FAIL, PENDING,
  UNKNOWN, EMPTY, WARNING). The per-commit AHEAD / BEHIND / DIVERGENT variants are
  deleted.
- `jaspr status` simplifies: no more divergence classifier in the status render path;
  remote-only commits collapse from a list to a one-line summary.
- `DivergenceClassifier` stays in production code; `jaspr compare` uses it.
- Three commands change in the same window: `status` (refactor), `compare` (new),
  `graph` (new). Tracked under parent bead `git-jaspr-k93` with child beads
  `git-jaspr-9ag`, `git-jaspr-34z`, and `git-jaspr-xbc`.
- The shared-index UI convention propagates: any future command that pairs two
  sequences (e.g., `jaspr pull --dry-run`) should follow the same `[N]`
  cross-reference convention.
- Future `jaspr pull` can consume `compare`'s output model (the `CompareRow` shape)
  directly rather than re-deriving alignment.

## Future Considerations

- `compare --with-target`: optional flag to overlay the target branch position.
  Mentioned during design as a stretch goal; punted to `jaspr graph` for now.
- Compressing identical prefix runs (e.g., "8 commits identical above") on long
  aligned stacks, to keep `compare` scannable. Decide during implementation.
- A `compare --strict` exit code mode if users want script-friendly drift detection
  later.
