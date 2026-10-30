# AGENTS.md

How people and AI agents (Claude Code, Codex, ...) work on this repository: the project, the
team workflow, and the technical notes for building, testing and changing it. Read all of it at
the start of every session. `CLAUDE.md` only imports this file.

## What this is

[SBW] NPC Squads (`sbwnpc`) is an NPC squad addon for the SuperbWarfare weapons and vehicles mod,
written in Kotlin for NeoForge and Fabric on Minecraft 1.21.1 and for Forge on 1.20.1. Players
recruit, deploy and command squads of AI-driven NPCs (riflemen, medics, snipers, machine gunners,
mortar crews, tank crews, drone operators, ...) that fight, take cover, ride vehicles and follow
orders (Attack, Defend, Patrol, Move).

It is a hobby project built entirely through AI-assisted development. Human playtesting,
including multiplayer, catches what actually breaks in-game. "It compiles" and "it is tested" are
two different claims.

## How the AI decides (read before changing any AI)

The AI is built on agreed patterns, described in
[docs/AI-ARCHITECTURE-DESIGN.md](docs/AI-ARCHITECTURE-DESIGN.md). Read that document before any
change to how NPCs, squads or operations behave. A change that does not fit these layers is a
design change: agree it with the owner before writing code.

- **Order, situation, strategy, plan and execution are separate types** (`IssuedCommand`,
  `FactSnapshot`, `SquadStrategy`, the HTN plan, `PlanRuntime`). The situation is a set of facts
  that hold at once, not a table of mutually exclusive states. A strategy reads the snapshot and
  proposes; it never calls navigation or changes another level's tasks.
- **Utility** compares the allowed options (the squad strategy today, the operation by design).
  Options that are unreachable or forbidden are excluded before scoring.
- **HTN** decides how a goal is carried out, at each of the five levels in its own domain
  (`TacticalDomain` and its siblings): the operation, the squad, the team, and the NPC choosing his
  position, cover, path and target. A new manoeuvre is new tasks, methods and conditions, not a new
  mechanism beside the planner. Every compound task keeps a last-resort method (`hold_as_ordered`,
  #172), so no man is left without a plan.
- **SmartBrainLib behaviours are execution adapters**: they walk, aim, fire, heal and board as the
  planner chose. Outside the planner only the design's explicit mandatory rules decide: authority,
  safety, immediate reactions to danger (a live grenade, a drone closing in), and completion and
  refusal criteria. The existing ammunition rules, the repeat of an ATTACK after a refusal and the
  priority of medical help stay as they are until a change is agreed separately.
- **Suspending the planner is an exception.** A behaviour or `NpcActivity` that takes a man's feet
  from the plan (`SUSPENDED(higher_priority_action)` in the decision trace) for a new reason, or a
  new memory type that starts one, needs the owner's approval. `DecisionLayerRuleTest` lists every
  behaviour and sensor, every such activity and every memory type the mod registers, so a new one
  fails the build until it is added there with the reason and the decision behind it.
- When in doubt whether something is a decision or an execution, treat it as a decision and ask.

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
- **Keep the `Status` current as the work moves.** Move the issue to **In review** when its PR is
  open or the change waits for an in-game check, with a comment on what was verified. Move it back
  to **In progress** if review or the in-game check finds a problem. **Done** only after the
  change is merged into `develop`, and confirmed in-game where that applies.
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
- **One issue, one branch**, named `<type>/<short-kebab-description>` so the name says what the
  branch does: `feature/add-mortar-barrage`, `fix/medic-heals-enemies`, `perf/cache-cover-search`,
  `docs/architecture-overview`. The type matches the commit prefix it will mostly carry
  (`feature/` for `feat:`, otherwise the same word: `fix/`, `perf/`, `refactor/`, `docs/`,
  `test/`, `chore/`). Lowercase, hyphens between words, a few words at most, no issue numbers or
  dates on their own. Bring `develop` into a long-lived branch regularly, and before opening a PR.
- **Agents name the branch themselves.** A tool's default branch name, such as a cloud session's
  `claude/...`, or a random suffix (`chore/add-local-setup-o3uh8c`) does not follow the rule
  above. Rename the branch before the first push.
- **End of a session:** nothing is left uncommitted. Commit it to your branch, even as an
  unfinished WIP commit, and update the issue with where it stands and what is next.
- **One job per worktree.** Run one headless or Gradle job in a worktree at a time, and don't edit
  or build in a tree while its headless run is going. A fresh worktree needs `SuperbWarfare/`,
  `build/headless/downloads` and `tools` linked from the main checkout, or an `--offline`
  bootstrap fails.
- **`gh pr merge --delete-branch` also deletes the branch's checked-out worktree directory.** Merge
  without it, or leave the worktree first, and remove the branch and worktree by hand.
- **Remove what is finished:** delete merged branches, remove worktrees you no longer use, and
  close issues the PR resolved.
- Don't leave generated files, archives or session dumps in the repository root. Keep them out of
  the tree or out of git.

## Changes, verification and publishing

- **Workflow (owner's rule) for every change to how NPCs, squads or operations act:** a written
  plan, an independent review of the plan by a separate agent, implementation, tests, and an
  independent review of the test results and the diff. Say in the PR what each review found. The plan names the layer each decision lives in (the HTN task or method, the utility
  option, the state, the strategy) or the explicit safety rule that justifies doing it elsewhere,
  and both reviews check that against `docs/AI-ARCHITECTURE-DESIGN.md`. A plan that cannot name
  its layer goes to the owner first.
- Before implementing a feature, trace its callers and the state it shares with neighbouring
  behaviours: navigation, targeting, cover, weapon ownership, supply and role locks. Keep existing
  contracts, or change one on purpose and test the changed contract.
- Prefer an existing vanilla, NeoForge or SuperbWarfare class or mechanism over a new one. Check
  with the owner before building a parallel system for something SBW or vanilla already does.
- **The mod is written for several loaders** (NeoForge and Fabric on 1.21.1, Forge on 1.20.1), so
  write every change with that in mind. Shared code in `common` must not import loader API. What
  differs between loaders goes into the loader module, or into the per-version file that exists
  once per Minecraft version under the same name. When a change touches an API that differs,
  such as events, networking, saved data, blocks, config or rendering, say in the PR how each
  loader gets it. `docs/LOADER-PORTS.md` lists the seams and the known gaps.
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
- **Your own feature branch:** push after every commit, without asking, so the work in progress
  is visible to everyone.
- **`develop`, `master` or someone else's branch:** push to it, merge into it or open a PR into it
  only with explicit approval from the owner for that specific step. Approval does not carry over
  to the next one.
- A PR links its issue (`Closes #N`), says what changed and why, and lists how it was verified.
- When the person you work for says to slow down ("не торопись" or similar), stop and wait.
  Don't keep proposing or making the next change.

## Pull requests

A PR is read by someone with limited time. Every line of the diff and the description should earn
its place.

The diff:

- One concern per PR. A bug fix doesn't carry a drive-by refactor, a rename sweep or reformatting
  of untouched code; open a separate PR for those, or leave them.
- Keep it as small as the change allows. If the PR description needs "also" more than once, split it.
- Touch only what the change needs: no whitespace or import churn, no reordered members, no
  rewritten comments that were fine.
- No leftover debug logging, commented-out code, unused helpers, or "just in case" parameters,
  null checks and fallbacks for cases that can't happen.
- No comments that narrate the diff ("added this to fix the bug", "new method"). Comments follow
  the comment rules below, and the history belongs in the commit message.
- Re-read the whole diff before opening the PR, as a reviewer would, and cut whatever isn't needed.

The description:

- Title: a Conventional Commits line, the same style as commit messages.
- Body: `Closes #N`, then what changes for the player or the code, and why, in a few plain
  sentences. Then how it was verified, stated honestly: "built, unit tests pass, not yet tested in
  game" is a valid answer. Add known risks or open questions only if there are any.
- Don't restate the diff file by file, list every commit, or repeat the title.
- No filler: no "This PR introduces...", no "comprehensive", "robust", "seamless" or "enhanced",
  no emoji headings, no summary tables for a three-line change, no "future improvements" nobody
  asked for, no empty template sections.
- A PR that changes gameplay stays a draft until it has been tested in game.

## Code comments and KDoc

Say *why*, not *what*; explain what is surprising or a workaround, so nobody "simplifies" it back
into the bug; write in English. KDoc goes on every class, object and non-obvious public or
`internal` member. Never put a literal `/*` inside a KDoc comment (see the gotcha below). The full
rules are in [docs/CODE-STYLE.md](docs/CODE-STYLE.md).

## Talking to each other

- Issues, PRs, commit messages and repository docs are written in English. The repository is
  public.
- The README is for players only: no internals, no performance numbers.
- **Aim to do better than asked.** When you are asked to do something "this way" and you know a
  better way, or something worth adding that is more correct in meaning or logic, propose it. Just
  closing the task as written only works if the person spells out the exact state they want, and a
  person is the slow part: every missing detail costs a round trip and slows development down.
- If two tasks touch the same code, such as tactics, navigation or the map, say so in both issues
  before starting, so nobody's work gets overwritten.

## Build, test, deploy

### Build

- First time on a machine: `scripts/setup.sh` checks Java, resolves the pinned SBW artifact
  described below and runs `build`. Pass Gradle tasks to run those instead
  (`scripts/setup.sh buildDevelop`), `--deploy` to copy the jar into `~/.minecraft/mods/`, and
  `--refabricated` to also clone and build a development checkout of
  [SuperbWarfare ReFabricated](https://github.com/CrawKatt/SuperbWarfareReFabricated) (the Fabric
  fork, branch `0.8.9.2-1.21-Fabric`) into the gitignored `SuperbWarfareReFabricated/`. That
  checkout is for the Fabric port work (#15) and is separate from the pinned addon dependency.
  `scripts/setup.sh --help` lists the overrides. On Windows, run it from Git Bash or WSL.
- `./gradlew build` is the same as `buildProduction` (`DebugFlags` off). For local testing with
  verbose trace logs and debug particle markers, use `./gradlew buildDevelop`: the flag is baked
  into the jar by the generated `BuildFlags` object (`generateBuildFlags` in
  `common/build.gradle.kts`) and cannot be passed at runtime.
- Loaders: NeoForge is the default. `./gradlew -Ploader=fabric :fabric:build` and
  `./gradlew -Ploader=forge :forge:build` build the other two, and only the loader chosen with
  `-Ploader` is configured. Status and decisions: `docs/LOADER-PORTS.md`.
- Minecraft 1.21.1 (NeoForge, Fabric) and 1.20.1 (Forge) are the targets. A 1.21.6 or newer port
  was investigated and shelved: SuperbWarfare's own port to that target isn't ready, and this
  addon relies on Create/Ponder-style APIs that block on it.
- SBW inputs are pinned in `gradle/sbw/`; normal builds use the published artifact for the
  loader and verify SHA-512 before compilation and testing. Provenance:
  `build/reports/sbw/input.txt`. Use `-PsbwSourceDir=/absolute/path` only for local development;
  the checkout must match the loader/Minecraft target and its revision/dirty state is reported.
  CI rejects this override.
- The pinned SBW version is what the addon is built against, not what players need. Players need
  SBW 0.8.9.1 or newer: `neoforge.mods.toml` declares `[0.8.9.1,)`, with no upper bound. Building
  against a newer patch keeps the most players covered. When the pin moves, keep that lower bound
  unless the addon starts relying on an API the older version lacks; then raise it on purpose.

### Test and check

- Compiling (`compileKotlin`) is not a testable artifact. A real jar requires `build`.
- Unit tests: `./gradlew test`.
- Code smells: `./gradlew detekt`, which `check` and `build` also run for each module. It runs
  detekt's code-smell rules only (`config/detekt/detekt.yml`); formatting is ktlint's job
  (`formatKotlin`, `checkKotlinStyle`, see `docs/CODE-STYLE.md`). Findings that predate detekt are
  in each module's `detekt-baseline.xml`. Fix new findings rather than adding them to the baseline,
  and run both before opening a PR.
- Headless GameTests: `bash setup-minecraft-headless.sh --prepare` once, then the script alone
  for three real server smoke scenes, `--unit` for JUnit, `--filter methodA,methodB --report name`
  for selected scenes, or `--group overwatch` (or `--group combat,perception`) for a whole domain.
  `docs/HEADLESS-TESTS.md` covers dependencies, caches, logs, the list of groups and full-suite runs.
- A GameTest for a feature goes in the feature's own branch, written first and red, so it is
  green when the feature lands. A new test class belongs in a group (`GameTestGroups`), or the
  server refuses to start.

### Install for in-game testing

- After checks pass, build with `buildDevelop` and install the jar locally by default. An explicit
  instruction not to build or install overrides this; otherwise it needs no separate confirmation.
- Install into the **flat** `~/.minecraft/mods/` folder:
  ```
  python3 install-local-mod.py build/libs/sbwnpc-<version>-mc1.21.1-neoforge.jar
  ```
  Not into a `~/.minecraft/mods/1.21.1/` subfolder: the launcher doesn't read it. Remove any jar
  of another version from `mods/` first, so two versions of the mod don't load at once.
- Local test pack for any loader: `python3 scripts/localpack.py <fabric|forge|neoforge> [--mrpack]` downloads the
  pinned SuperbWarfare, the addon's dependencies and quality-of-life mods (lists in `scripts/localpack/`) from
  Modrinth into `build/localpack/<loader>/mods`, with the built addon jar. `--mrpack` writes a file that Prism
  Launcher or the Modrinth app imports, installing the loader too.
- Never overwrite a jar in place while Minecraft is running: the JVM caches ZIP offsets and later
  class loading can fail. The installer stages the new archive and swaps it in atomically. A full
  Minecraft restart loads the new build.

## Cloud sessions (Claude Code on the web)

`.claude/hooks/session-start.sh` runs `scripts/setup.sh testClasses` using the pinned SBW artifact.
Update its profile and SHA-512 together when intentionally changing the dependency, then rerun
the compatibility and build checks. Cloud setup does not clone a moving SBW branch. The network
allowlist the environment needs is in [docs/CLOUD-SESSIONS.md](docs/CLOUD-SESSIONS.md).

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

Full map of packages, wiring and where to add things: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
The short version:

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
- Only `integration/sbw/` (and the mixins) may import SuperbWarfare; everything else goes through
  the ports in `domain/port/`. `SbwBoundaryTest` enforces it.
- Runtime-only static state must be cleared in `ServerLifecycle.onServerStopped`, or it leaks into
  the next world.
