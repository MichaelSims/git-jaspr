# 0007: GitHub Stacks API Integration

Date: 2026-08-27

## Status

Shelved (2026-09-24). The integration was removed from `development` and lives only on the
`github-stacks` branch. See "Why this was shelved" below.

## Context

GitHub introduced a Stacks API (REST, API version 2026-03-10, public preview) that lets
pull requests be grouped into an ordered stack. The GitHub UI then shows a position indicator
on each PR (e.g. "2/5"), a navigable list of all PRs in the stack in the merge box, and a
"Part of a stack" label. Per-PR diff scoping is not a stacks feature; jaspr already achieves
that through chained base branches.

jaspr already creates exactly the branch chain the Stacks API requires: each PR's base ref
matches the previous PR's head ref. The question is how to register jaspr-managed PRs as a
GitHub stack so users get the native stack UI, while jaspr continues to own the merge
workflow (CLI fast-forward), branch management, and commit ordering.

Feasibility testing on 2026-08-27 confirmed:

- Creating a stack from jaspr's chained PRs works.
- CLI fast-forward merges work with the stack registered. GitHub auto-detects the merge,
  marks the PR as merged, and auto-retargets the next PR's base.
- The stack locks PR base branches via the GraphQL API. Any push that retargets bases
  (reorder, add/remove commits) must dissolve the stack first.
- GitHub's own `gh stack modify` also dissolves and recreates on restructuring.
- The Stacks REST API has no reorder endpoint; dissolve-and-recreate is the intended pattern.

## Decision

Use the GitHub Stacks REST API directly (not `gh stack link`) to register jaspr PR stacks.

The REST approach was chosen over `gh stack link` because:

- jaspr already has a Ktor HTTP client with bearer token auth in `GitHubClientWiring`.
  The REST endpoints are simple enough that a dedicated client is small.
- Shelling out to `gh stack link` adds a runtime dependency on the GitHub CLI with stacks
  support, which not all users will have.
- Direct API access gives full control over error handling, retry, and the auto-detection
  probe.

The integration follows three rules:

1. Before any push that might retarget PR bases, dissolve the existing stack (if one exists).
2. After push completes, re-register the stack with the current ordered list of PRs.
3. During merge, tolerate the base-retarget error from the stack lock and let GitHub's
   auto-retarget handle it.

Auto-detection: jaspr probes the stacks endpoint on the first push of a session. If it
responds successfully, stacks are available and jaspr registers them. If it fails (404,
version header rejected), jaspr silently skips stack registration for the rest of the session.
A git config override (`jaspr.githubStacks`) allows forcing the feature on or off.

## Consequences

- Each push that retargets PR bases (reorder, add/remove commits) creates a new stack
  number. The stack number is not stable across pushes. This matches GitHub's own behavior
  with `gh stack modify`.
- The feature is opt-in via auto-detection: repos on GitHub plans that support the stacks
  API get it automatically, repos that don't are unaffected.
- The Stacks API is in public preview and may change. The integration is isolated behind a
  dedicated client interface, so changes to the API surface can be absorbed without touching
  the core push/merge logic.

## Why this was shelved

GitHub allows a pull request to belong to only one stack, and stacks must be linear. jaspr
supports stacks that share commits, e.g. `[A B C D]` and `[A B E F]` where A and B are common
to both. GitHub can't represent that.

Probing the API on 2026-09-24 confirmed this:

- Creating `[A B E F]` while `[A B C D]` is registered fails with 422: "Pull requests #A, #B
  are already part of a stack".
- A stack can sit on another stack's branch (`[E F]` with base `B` is accepted), but it can't
  fork: adding `E` to `[A B C D]` fails with "Pull requests must form a stack, where each PR's
  base ref is the previous PR's head ref".
- Changing the base of a stacked PR fails with "Cannot change the base branch because the pull
  request is part of a stack."

GitHub's docs say stacks "can't include forks or branching structures". They don't state the
one-stack-per-PR rule outright, but the data model (`PullRequest.stack` is singular) and
GitHub's own `gh stack` CLI ("A PR can only belong to one stack") both assume it.

With overlapping jaspr stacks, each push would dissolve the other stack's GitHub stack and
register its own, so the two would keep undoing each other. We considered registering a GitHub
stack only when a jaspr stack has no overlap. That leaves the feature off in exactly the
workflow where it would be most useful, one person with several stacks sharing a base, so it
wasn't worth keeping.

The feature is also still in public preview. It's worth revisiting if GitHub lets a pull
request belong to more than one stack.
