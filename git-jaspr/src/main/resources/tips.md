# Jaspr Tips

These tips are shown periodically after commands to help you get the most out of jaspr.
Lines beginning with `- ` are parsed as individual tips.

- If you installed via Homebrew, you can tab complete jaspr commands and options. Try it!
- Jaspr works best when you address PR feedback by amending the relevant commit rather than adding fixup commits. You can amend older commits in your stack via `jaspr edit`, then push your updated stack via `jaspr push`.
- If you decide you no longer need a commit in your stack, use `jaspr edit` and delete the line for that commit from the list. When you `jaspr push` later, the PR will be abandoned and you can later delete it via `jaspr clean`.
- Jaspr requires a name for each stack you push. This is to aid in collaboration as well as enable detection of abandoned PRs. If you don't want to be prompted for a name, pass `--name my-name` as an option to `jaspr push`.
- Jaspr supports custom themes! Check your config file (`~/.jaspr.properties`) for examples, or run `jaspr preview-theme --theme <name>` to test one out.
- Not ready for review? Prefix your commit message with `DRAFT` or `WIP` and jaspr will automatically create a draft PR for that commit.
- Run `jaspr status` to see an overview of your stack — which PRs have passing checks, are approved, or need attention.
- Keep your stack up to date with the target branch by running `jaspr rebase`. This fetches the latest changes and rebases your stack in one step.
- After your PRs are merged, run `jaspr clean` to interactively remove leftover local and remote branches.
- Working on multiple stacks? Use `jaspr checkout` to interactively switch between your named stacks.
- Don't want to wait for checks to pass? Run `jaspr auto-merge` and jaspr will poll for check completion and merge automatically when ready.
- Only want to push part of your stack? Use `jaspr push -c 3` to push just the bottom 3 commits, or `jaspr push -c -1` to exclude the top commit.
- If you hit a merge conflict during `jaspr edit` or `jaspr rebase`, resolve the conflict, stage the files with `git add`, then run `git rebase --continue`.
- Have a work-in-progress commit you never want to accidentally push? Start it with `dont push` (or `dont-push` / `dontpush`) and jaspr will skip it.
- Run `jaspr init` to generate a config file with all available options and documentation. Per-repo config (`.jaspr.properties`) overrides your user-wide config (`~/.jaspr.properties`).
