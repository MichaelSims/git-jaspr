---
name: jaspr-test-shell
description: Open an iTerm window in a git-jaspr test's temp directory for interactive testing with the local jaspr build.
disable-model-invocation: true
argument-hint: [temp-dir-path]
---

Open an iTerm window in a GitHubTestHarness temp directory so the user can interactively run
git-jaspr commands against a test repository.

## Behavior

1. **Build the local jaspr distribution** by running `./gradlew :git-jaspr:installDist` so the
   alias points to an up-to-date binary. Run this before opening iTerm.

2. **Determine the target directory:**
   - If `$ARGUMENTS` is provided, use it as the temp dir path. It may be a plain Unix path or a
     `file:` URI (e.g. `file:///tmp/GitHubTestHarness123/`). If it starts with `file://`, strip
     that prefix (and any trailing slash) to obtain the filesystem path. The path may point to
     either the parent temp dir or the `local` subdir directly.
   - If no argument is provided, find the most recent `GitHubTestHarness*` directory under the
     system temp directory. Use `${TMPDIR%/}` (strip trailing slash) to avoid glob issues, e.g.:
     `ls -dt "${TMPDIR%/}"/GitHubTestHarness* 2>/dev/null`
     If multiple exist, list them with timestamps and ask the user to pick one.

3. **Validate** that the directory exists and contains a `local` subdir (unless the path already
   ends in `local`).

4. **Open iTerm** in the `local` subdir using `osascript` with the following setup commands:
   - `sdk use java 21.0.1-graalce`
   - Create an alias: `alias jaspr='$HOME/IdeaProjects/git-jaspr/git-jaspr/build/install/git-jaspr/bin/git-jaspr'`
   - Create a convenience alias: `alias jaspr-status='jaspr status git-hub-test-harness-remote'`
   - Print a short help message so the user knows what's available

Use this osascript template (fill in `$LOCAL_DIR`):

```bash
osascript <<EOF
tell application "iTerm"
    activate
    create window with default profile
    tell current session of current window
        write text "cd '$LOCAL_DIR' && sdk use java 21.0.1-graalce && alias jaspr='$HOME/IdeaProjects/git-jaspr/git-jaspr/build/install/git-jaspr/bin/git-jaspr' && alias jaspr-status='jaspr status git-hub-test-harness-remote' && echo '\\nReady. Aliases: jaspr, jaspr-status\\n'"
    end tell
end tell
EOF
```

5. Confirm to the user that the iTerm window was opened and which directory it points to.