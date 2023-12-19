# Git Jaspr (Just Another Stacked Pull Request)

This is a reimplementation of [git spr](https://github.com/ejoffe/spr) written in Kotlin.

# Why

Because I like Gerrit, and wish GitHub were Gerrit. But since it isn't, I have to use this tool instead.
For more rationale see this [excellent blog post](https://jg.gg/2018/09/29/stacked-diffs-versus-pull-requests/).

# How

## Installing

Download the appropriate standalone binary for your platform (Linux or OS X, sorry Windows users, you can run the Java
version maybe?) and install it into your `PATH` somewhere (`~/.local/bin`?) and then create a configuration file in 
`~/.git-jaspr.properties` with the following contents:

```properties
github-token=<GH PAT> # required
use-cli-git-client=true # optional, run a help command for more info
```

Where `<GH PAT>` is a GitHub Personal Access Token (classic) with the permissions `read:org`, `read:user`, `repo`, and 
`user:email`.

## Using

Some commands to try:
```shell
$ git jaspr -h 
$ git jaspr status
$ git jaspr push
$ git jaspr merge
$ git jaspr auto-merge
$ git jaspr clean
```

Any of the above can be invoked with `--help` (except just `git jaspr` which requires `-h` for reasons not worth going
into).

Enjoy!

## Note on GitHub branch protection rules and rulesets

GitHub branch protection rules and rulesets are both complex features, and I won't pretend to be an expert. There are
many configurations that are likely valid, so ultimately what works, works. But there are some considerations to keep in
mind with regard to using Jaspr:

- At a minimum you should have a workflow that triggers on pull requests (either for all branches or at least for `main`
  and for `jaspr/**/*`) that runs checks and reports status back to GitHub. Likely you want a branch protection rule
  that requires status checks to pass before merging to `main` at least.
- If `git jaspr status` does not show the "pr approved" checkmark or x, you may need to create a branch protection rule
  that requires pull request approvals before merging first. (I've noticed this behavior is inconsistent... sometimes
  this bit shows even without this requirement.)
- You want to make sure that people can force push to `jspr/**/*`. Jaspr's philosophy is that of Gerrit's, which is to
  favor a trunk based workflow where PR remediation is done via amends/edits and are force pushed (this is why Jaspr
  pushes revision history branches and includes diff links for them in the PR descriptions).

## Note on functional tests

This repo contains functional tests that work with a real GitHub remote and actually open/modify/close PRs. To use these
tests, you will need to set some configuration options in your `~/.git-jaspr.properties` file:

```properties
github-test-harness.githubUri=git@github.com:some-project.git
github-test-harness.userKey.michael.name=Michael Sims
github-test-harness.userKey.michael.email=michael@example.com
github-test-harness.userKey.michael.githubToken=<redacted>
github-test-harness.userKey.derelictMan.name=Frank Grimes
github-test-harness.userKey.derelictMan.email=frank@example.com
github-test-harness.userKey.derelictMan.githubToken=<redacted>

```

Replace the `<redacted>` with GitHub classic PATs with the same permissions required by Jaspr. The `githubUri` should be
the URI of a test project that the tests can push to and open PRs to, etc. The repo should have a workflow triggered on
PRs that runs a fake verify script:

Workflow:
```yaml
name: Pull Request

on:
  pull_request:
  workflow_dispatch:

jobs:

  build:
    name: Build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
        name: Checkout repo
        with:
          fetch-depth: 0
      - run: git checkout origin/${{ github.event.pull_request.head.ref }}

      - name: Verify
        run: |
          chmod a+x verify.sh
          ./verify.sh

```

verify.sh:
```shell

#!/usr/bin/env bash

set -x
set -e

# Keeping around this commented code in case github decides to start being a pain again
#git log --graph --all
# shellcheck disable=SC2034
#gitOutput=$(git show --pretty=full)

verifyDelay=$(git show --pretty=full | awk '/^    verify-delay:/ { print $2 }')
verifyResult=$(git show --pretty=full | awk '/^    verify-result:/ { print $2 }')

if [[ "$verifyDelay" -gt 0 ]]; then
  echo "Delaying $verifyDelay seconds"
  sleep "$verifyDelay"
fi

if [[ "$verifyResult" ]]; then
  echo "Exiting with code $verifyResult"
  exit "$verifyResult"
else
    echo "Verify result not found in commit body, exiting success by default"
    exit 0
fi
```

If you try to run these and have questions, please contact me or open an issue and I'll try to help.
