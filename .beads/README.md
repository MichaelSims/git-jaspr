# Beads - AI-Native Issue Tracking

Welcome to Beads! This repository uses **Beads** for issue tracking - a modern, AI-native tool designed to live directly in your codebase alongside your code. Issues are versioned on the `beads-sync` orphan branch, separate from code history. Run `beads-sync` to export and commit.

## What is Beads?

Beads is issue tracking that lives in your repo, making it perfect for AI coding agents and developers who want their issues close to their code. No web UI required - everything works through the CLI and integrates seamlessly with git.

**Learn more:** [github.com/steveyegge/beads](https://github.com/steveyegge/beads)

## Quick Start

### Essential Commands

```bash
# Create new issues
br create "Add user authentication"

# View all issues
br list

# View issue details
br show <issue-id>

# Update issue status
br update <issue-id> --status in_progress
br update <issue-id> --status done

# Sync issues and commit to beads-sync branch
beads-sync
```

### Working with Issues

Issues in Beads are:
- **Local-first**: Stored in `.beads/issues.jsonl`
- **AI-friendly**: CLI-first design works perfectly with AI coding agents
- **Always in sync**: Run `beads-sync` to export and commit to the `beads-sync` branch

## Why Beads?

✨ **AI-Native Design**
- Built specifically for AI-assisted development workflows
- CLI-first interface works seamlessly with AI coding agents
- No context switching to web UIs

🚀 **Developer Focused**
- Issues live locally, right next to your code
- Works offline, fully local
- Fast, lightweight, and stays out of your way

🔧 **Simple Sync**
- Run `beads-sync` to export and commit to the `beads-sync` orphan branch
- Issues versioned separately from code history

## Get Started with Beads

Try Beads in your own projects:

```bash
# Install Beads
curl -sSL https://raw.githubusercontent.com/steveyegge/beads/main/scripts/install.sh | bash

# Initialize in your repo
br init

# Create your first issue
br create "Try out Beads"
```

## Learn More

- **Documentation**: [github.com/steveyegge/beads/docs](https://github.com/steveyegge/beads/tree/main/docs)
- **Quick Start Guide**: Run `br quickstart`
- **Examples**: [github.com/steveyegge/beads/examples](https://github.com/steveyegge/beads/tree/main/examples)

---

*Beads: Issue tracking that moves at the speed of thought* ⚡
