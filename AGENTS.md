# AGENTS.md

How people and AI agents (Claude Code, Codex, ...) work on this repository: the project, the
team workflow, and the technical notes for building, testing and changing it. Read all of it at
the start of every session. `CLAUDE.md` only imports this file.

## What this is

[SBW] NPC Squads (`sbwnpc`) is an NPC squad addon for the SuperbWarfare weapons and vehicles mod,
built on NeoForge 1.21.1 and Kotlin. Players recruit, deploy and command squads of AI-driven NPCs
(riflemen, medics, snipers, machine gunners, mortar crews, tank crews, drone operators, ...) that
fight, take cover, ride vehicles and follow orders (Attack, Defend, Patrol, Move).

It is a hobby project built entirely through AI-assisted development. Human playtesting,
including multiplayer, catches what actually breaks in-game. "It compiles" and "it is tested" are
two different claims.

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
- **Taking a task: assign it to yourself first**, before any work on it, so nobody else takes
  it. Then move it to **In progress** and say in a comment which branch holds the work.
- Don't start on an issue that has an assignee other than you. Ask in a comment instead.
- Something you found mid-task that isn't part of the task, such as a bug or an idea: file a new
  issue (usually **Backlog**) instead of widening the current change.
- Issues are work items for agents. A question for the person you work for is asked in your
  session, not filed as an issue. Once it is answered, write the decision into the issue it
  affects.
- Tasks for people, such as an in-game check, are given to the person you work for in your
  session, and listed in the PR's verification steps. They are not filed as issues.
- A Backlog issue must make sense to someone without your session: what happens, where (files,
  log lines), how to reproduce it, and what is already known or decided.
- A local `TODO.md` is fine as a personal scratch pad. It is excluded from git and does not
  replace the board.

## Keeping the tree current

- **Start of a session:** `git fetch`, check `git status`, `git worktree list` and the board.
  Find out what changed in `develop` since your branch left it, and who is working on what.
- **Branches:** `develop` is the default branch. All work branches from it and comes back to it
  through a PR. `master` is behind `develop` and not used for new work. Ask the owner before
  touching it.
- **One issue, one branch**, named after the change: `feature/...`, `fix/...`, `docs/...`,
  `chore/...`. Bring `develop` into a long-lived branch regularly, and before opening a PR.
- **End of a session:** nothing is left uncommitted. Commit it to your branch, even as an
  unfinished WIP commit, and update the issue with where it stands and what is next.
- **Remove what is finished:** delete merged branches, remove worktrees you no longer use, and
  close issues the PR resolved.
- Don't leave generated files, archives or session dumps in the repository root. Keep them out of
  the tree or out of git.

## Changes, verification and publishing

- Before implementing a feature, trace its callers and the state it shares with neighbouring
  behaviours: navigation, targeting, cover, weapon ownership, supply and role locks. Keep existing
  contracts, or change one on purpose and test the changed contract.
- Prefer an existing vanilla, NeoForge or SuperbWarfare class or mechanism over a new one. Check
  with the owner before building a parallel system for something SBW or vanilla already does.
- **Commit as the work lands**, not in a batch at the end: a finished piece that compiles and
  passes tests is committed before the in-game check. If that check shows a commit was wrong, fix
  it with a follow-up commit, or rework the unpushed commits locally.
- Prefer several small commits, split by what each change does. Don't bundle unrelated doc-only
  changes into a commit unless they were asked for.
- Commit messages are concise and start with a Conventional Commits prefix (`feat:`, `fix:`,
  `docs:`, `refactor:`, `perf:`, `test:`, `chore:`, ...) that matches what the commit is.
- **Compiling is not testing.** Record in the issue or PR exactly what ran: unit tests, which
  GameTest scenes, which in-game scenario, and what you saw. Say plainly what was not checked.
- **Behaviour changes need an in-game check by a human** before they are merged. Put the steps to
  check in the PR.
- **Pushing, opening PRs and merging** are publishing steps. An agent does them only with
  explicit approval from the person it works for, for that specific push. Approval does not
  carry over to the next push. Don't push directly to `develop` or `master` unless the owner asks
  for exactly that.
- A PR links its issue (`Closes #N`), says what changed and why, and lists how it was verified.
- When the person you work for says to slow down ("не торопись" or similar), stop and wait.
  Don't keep proposing or making the next change.

## Talking to each other

- Issues, PRs, commit messages and repository docs are written in English. The repository is
  public.
- The README is for players only: no internals, no performance numbers.
- If two tasks touch the same code, such as tactics, navigation or the map, say so in both issues
  before starting, so nobody's work gets overwritten.

## Build, test, deploy

- Build: `./gradlew build`, the same as `buildProduction` (`DebugFlags` off).
- For local testing with verbose trace logs and debug particle markers: `./gradlew buildDevelop`.
  The flag is baked into the jar at build time by the generated `BuildFlags` object (see
  `generateBuildFlags` in `build.gradle.kts`). It cannot be passed at runtime.
- Tests only: `./gradlew test`.
- Cloud or headless setup: `bash setup-minecraft-headless.sh --prepare`. Then run
  `bash setup-minecraft-headless.sh` for three real server smoke scenes, `--unit` for JUnit, or
  `--filter methodA,methodB --report name` for selected GameTests. `docs/HEADLESS-TESTS.md` covers
  dependencies, caches, logs and full-suite runs.
- Compiling (`compileKotlin`) is not a testable artifact. A real jar requires `build`.
- Deploy for in-game testing into the **flat** `~/.minecraft/mods/` folder:
  ```
  python3 install-local-mod.py build/libs/sbwnpc-<version>-mc1.21.1.jar
  ```
  Not into a `~/.minecraft/mods/1.21.1/` subfolder: the launcher doesn't read it. Remove any jar
  of another version from `mods/` first, so two versions of the mod don't load at once.
- Never overwrite a jar in place while Minecraft is running: the JVM caches ZIP offsets and later
  class loading can fail. The installer stages the new archive and swaps it in atomically. A full
  Minecraft restart loads the new build.
- After checks pass, build with `buildDevelop` and install the jar locally by default. An explicit
  instruction not to build or install overrides this; otherwise it needs no separate confirmation.
- The addon depends on a local SuperbWarfare checkout via `includeBuild("SuperbWarfare")` in
  `settings.gradle.kts`, because it needs SBW's internal APIs, not just its published jar.
  `/SuperbWarfare/` is gitignored and maintained separately.
- The project stays on NeoForge 1.21.1. A 1.21.6/Fabric port was investigated and shelved:
  SuperbWarfare's own port to that target isn't ready, and this addon relies on Create/Ponder-style
  APIs that block on it.

## Cloud sessions (Claude Code on the web)

- `.claude/hooks/session-start.sh` clones SuperbWarfare (branch `0.8.9.2-1.21` by default,
  override with `SBW_BRANCH`) and runs `./gradlew testClasses`, so a cloud session can build and
  test. Bump the branch when the local SuperbWarfare snapshot moves to a newer release.
- The cloud environment's network allowlist must include these domains, or the build fails while
  resolving dependencies:
  - Gradle and Java: `mirrors.cloud.tencent.com` (Gradle wrapper), `plugins.gradle.org`,
    `plugins-artifacts.gradle.org`, `repo.maven.apache.org`, `api.foojay.io`
  - SuperbWarfare source: `github.com`
  - NeoForge and Minecraft: `maven.neoforged.net`, `maven.parchmentmc.org`,
    `libraries.minecraft.net`, `launchermeta.mojang.com`, `launcher.mojang.com`,
    `piston-meta.mojang.com`, `piston-data.mojang.com`, `resources.download.minecraft.net`
  - This addon's mod repositories: `thedarkcolour.github.io`, `dl.cloudsmith.io`,
    `maven.blamejared.com`, `jitpack.io`
  - SuperbWarfare's mod repositories: `cursemaven.com`, `edge.forgecdn.net`,
    `mediafilez.forgecdn.net`, `api.modrinth.com`, `cdn.modrinth.com`, `maven.createmod.net`,
    `maven.ryanhcode.dev`, `maven.sighs.cc`, `maven.shedaniel.me`, `maven.theillusivec4.top`,
    `raw.githubusercontent.com`

## Gotchas worth knowing before you hit them

- **Kotlin block comments nest**, unlike Java and C. A literal `/*` inside a `/** ... */` KDoc
  comment, such as a glob path like `foo/*.json`, opens a nested comment that swallows the rest of
  the file as "unclosed comment". Don't put `/*` inside a doc comment.
- **`StreamCodec.composite(...)` tops out at 6 fields.** A network payload with more fields
  hand-writes its codec via `StreamCodec.of(encoder, decoder)`.
- **Raw Ctrl-key state isn't synced to the server** the way sneaking is. Client-side modifier keys
  for tool interactions go through `InputEvent.InteractionKeyMappingTriggered` and
  `Screen.hasControlDown()`, not a server-side player-state check.
- **Mutable-collection accessors (e.g. `SquadSelection.looseOf(...)`) return a live reference**,
  not a copy. Storing it as a "before" snapshot to diff against "after" makes both sides the same
  object, and the diff silently does nothing. Copy it first.
- **Vanilla `Entity.setGlowingTag()` is globally synced**: every nearby player sees it. For a
  "this is my selection" cue, use targeted particles
  (`ServerLevel.sendParticles(ServerPlayer, ...)`).

## Architecture pointers

- AI behaviours live under `entity/ai/*Behaviour.kt`, written against SmartBrainLib's
  behaviour-tree framework (`ExtendedBehaviour`, `BrainActivityGroup`, `MemoryModuleType`), not
  vanilla `Goal`/`GoalSelector`.
- Faction hostility runs on vanilla scoreboard teams (`SquadTeams`), one per faction
  (`sbwnpc_<faction>`). Players are never put on a team. Their chosen faction lives in
  `PlayerFactionRegistry`, a `SavedData`.
- Client-server messages are `CustomPacketPayload`s registered under `network/` via
  `RegisterPayloadHandlersEvent`.
- Squad state (`SquadManager`) and player faction choices (`PlayerFactionRegistry`) are both
  `SavedData`, persisted per world.
