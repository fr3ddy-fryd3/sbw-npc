# AGENTS.md

How people and AI agents (Claude Code, Codex, ...) work together on this repository. Read it at
the start of every session, together with [CLAUDE.md](CLAUDE.md), which holds the technical notes:
build, test, deploy, gotchas and architecture.

## Where the work lives

- **Every piece of work is an issue on the GitHub Project board
  [Development](https://github.com/users/fr3ddy-fryd3/projects/1).** New issues in this
  repository land on it automatically, in Backlog. If it is not on the board, nobody else knows
  it is being done, or that it still needs doing.
- Board columns (the `Status` field):
  - **Backlog**: worth doing, not urgent. Anyone may pick it up.
  - **Ready**: agreed with the owner as next work.
  - **In progress**: someone is working on it right now. It has an assignee.
  - **In review**: a PR is open, or the change waits for an in-game check.
  - **Done**: merged into `develop`, and confirmed in-game where that applies.
- `Priority` (P0 is urgent, P2 can wait) and `Size` (XS to XL) are set when an issue leaves
  Backlog. Larger work is a parent issue with sub-issues.
- Taking a task: assign yourself, move it to **In progress**, and say in a comment which branch
  holds the work. Don't start on an issue someone else has in **In progress**. Ask in a comment
  instead.
- Something you found mid-task that isn't part of the task, such as a bug, an idea, or an open
  question: file a new issue (usually **Backlog**) instead of widening the current change.
- A Backlog issue must make sense to someone without your session: what happens, where (files,
  log lines), how to reproduce it, and what is already known or decided.
- Decisions go into the issue, as a comment, when they are made. Chat sessions are not shared.
- A local `TODO.md` is fine as a personal scratch pad. It is gitignored or excluded, and it does
  not replace the board.

## Keeping the tree current

- **Start of a session:** `git fetch`, check `git status`, `git worktree list` and the board.
  Find out what changed in `develop` since your branch left it, and who is working on what.
- **Branches:** `develop` is the default branch. All work branches from it and comes back to it
  through a PR. `master` is behind `develop` and not used for new work. Ask the owner before
  touching it.
- **One issue, one branch**, named after the change: `feature/...`, `fix/...`, `docs/...`,
  `chore/...`. Bring `develop` into a long-lived branch regularly, and before opening a PR.
- **End of a session:** nothing is left uncommitted. Commit it to your branch, even as an
  unfinished WIP commit, and update the issue with where it stands and what is next. A working
  tree full of unrelated, uncommitted changes is what forces untangling work after the fact.
- **Remove what is finished:** delete merged branches, remove worktrees you no longer use, and
  close issues the PR resolved.
- Don't leave generated files, archives or session dumps in the repository root. Keep them out of
  the tree or out of git.

## Changes, verification and publishing

- Commit as you go, in small commits, each with a Conventional Commits prefix. The details are in
  CLAUDE.md.
- **Compiling is not testing.** Record in the issue or PR exactly what ran: unit tests, which
  GameTest scenes, which in-game scenario, and what you saw. Say plainly what was not checked.
- **Behaviour changes need an in-game check by a human** before they are merged. Put the steps to
  check in the PR.
- **Pushing, opening PRs and merging** are publishing steps. An agent does them only with
  explicit approval from the person it works for, for that specific push. Approval does not
  carry over to the next push. Never push directly to `develop` or `master`.
- A PR links its issue (`Closes #N`), says what changed and why, and lists how it was verified.

## Talking to each other

- Issues, PRs, commit messages and repository docs are written in English. The repository is
  public.
- The README is for players only: no internals, no performance numbers.
- If two tasks touch the same code, such as tactics, navigation or the map, say so in both issues
  before starting, so nobody's work gets overwritten.
