# `git kspr` (Git Kasper)

This is a reimplementation of [git spr](https://github.com/ejoffe/spr) written in Kotlin.

## Braindump of plan

- CLI separated from main logic (naturally)
- Use Github's graphql API
  - Create a set of integration tests, but stub the GH API so that it returns actual canned responses, so I can iterate without needing a token or without worrying about the API throttling me
- Mostly achieve feature parity with git spr, but drop things I don't plan to use
- Use JGit instead of the command line
- Tests don't mock git, but actually create a temporary repo to operate on
- Features to add
  - Consider minimizing re-verifications
    - Merging part of the stack currently causes a reverify. It uses the GH API to merge instead of pushing directly, and the GH merge button tends to recreate the commits instead of FF merging them
  - Branch cleanup
    - An option to remove the branches for merged PRs
    - A command to interactively clean PRs that you opened (to handle orphaned ones)
  - When determining if a PR is "mergeable", don't use GH's "mergeable" property to determine this. Reason: if the branch protection rules for spr/**/* differ from those of main then GH may think a PR is mergeable when it really isn't. To fix this, we need to determine mergeability by inspecting the branch protection rules
  - Add a tracked Revision to the commit bodies when updating them. Then use this to warn if someone is attempting to kspr update an older version on top of a newer one. This will typically only happen when collaborating w/others on the same stack
  - Add command to list all PR stacks you have opened
- Figure out if there's a way to automate publishing like git spr does

## Change one
