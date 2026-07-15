# Jaspr Tips

These tips are shown periodically after commands to help you get the most out of jaspr.
Lines beginning with `- ` are parsed as individual tips.

- If you installed via Homebrew, you can tab complete jaspr commands and options. Try it!
- Jaspr works best when you address PR feedback by amending the relevant commit. To fix one anywhere in your stack, run `jaspr goto N` (or `jaspr up`/`down`) to check it out, make your change, `git commit --amend`, then `jaspr top` to replay the rest, and `jaspr push`.
- Decided you no longer need a commit in your stack? Navigate to it with `jaspr goto N` (or just stay at the top) and run `jaspr drop`. Its PR is abandoned on your next `jaspr push`, and `jaspr clean` removes the leftover branch.
- Jaspr requires a name for each stack you push. This is to aid in collaboration as well as enable detection of abandoned PRs. If you don't want to be prompted for a name, pass `--name my-name` as an option to `jaspr push`.
- Jaspr supports custom themes! Check your config file (`~/.jaspr.properties`) for examples, or run `jaspr preview-theme --theme <name>` to test one out.
- Not ready for review? Prefix your commit message with `DRAFT` or `WIP` and jaspr will automatically create a draft PR for that commit.
- Run `jaspr status` to see an overview of your stack — which PRs have passing checks, are approved, or need attention.
- Keep your stack up to date with the target branch by running `jaspr rebase`. This fetches the latest changes and rebases your stack in one step.
- After your PRs are merged, run `jaspr clean` to interactively remove leftover local and remote branches.
- Working on multiple stacks? Use `jaspr checkout` to interactively switch between your named stacks. If you have fzf installed, you'll get fuzzy search with a preview pane — scroll it with Shift+Up/Down.
- Don't want to wait for checks to pass? Run `jaspr auto-merge` and jaspr will poll for check completion and merge automatically when ready.
- Only want to push part of your stack? Use `jaspr push -c 3` to push just the bottom 3 commits, or `jaspr push -c -1` to exclude the top commit.
- If a jaspr operation stops on a merge conflict (during `jaspr rebase`, `jaspr sync`, or while replaying your stack), resolve it, stage the files with `git add`, then run `jaspr continue` (or `git rebase --continue`).
- Have a work-in-progress commit you never want to accidentally push? Start it with `dont push` (or `dont-push` / `dontpush`) and jaspr will skip it.
- Run `jaspr init` to generate a config file with all available options and documentation. Per-repo config (`.jaspr.properties`) overrides your user-wide config (`~/.jaspr.properties`).
- Spotted a fix that belongs to an earlier commit? Run `jaspr fixup` to pick the target commit interactively, then `jaspr rebase` folds the resulting `fixup!` commit in for you (autosquash is on by default).
- Want to lift a rename or refactor out of a feature commit so it comes first? Run `jaspr split` to break the commit back into working-tree changes, commit just the refactor, then `jaspr unsplit` to replay the original commit on top of your new precursor.
- Pushed from another machine, or did a teammate add to your stack? Run `jaspr pull` to fold remote-only commits into your local stack with no manual cherry-picking. If a commit has diverged, `jaspr pull --theirs` adopts the remote's version and saves a backup ref first.
- Curious how your local stack lines up with the remote? Run `jaspr compare` for a side-by-side view that flags reordered and content-diverged commits.
- Maintaining several stacks at once? Run `jaspr sync` to rebase all of your local stacks onto the latest target branch in one pass, not just the one you have checked out.
- Partway through a navigation session and want out? `jaspr nav finish` keeps the commits below your cursor, while `jaspr nav cancel` (alias `nav abort`) restores the branch you started on and aborts any half-finished cherry-pick.
- Lost track of where you are in a navigation session? Run `jaspr nav show` to reprint your stack and current position without moving — the same view you get after `jaspr up`/`down`.
- Jaspr periodically checks whether a newer version is available. To turn it off, pass `--no-update-check`, set `JASPR_NO_UPDATE_CHECK=1`, or add `update-check-enabled=false` to `~/.jaspr.properties`.
