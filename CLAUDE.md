# CLAUDE.md

Working notes for Claude Code (or any AI assistant) picking up this repository. Read this before making changes.

## What this is

[SBW] NPC Squads (`sbwnpc`) — an NPC squad addon for the SuperbWarfare weapons/vehicles mod, built on
NeoForge 1.21.1 / Kotlin. Players recruit, deploy, and command squads of AI-driven NPCs
(riflemen, medics, snipers, machine gunners, mortar crews, tank crews, drone operators...) that
fight, take cover, ride vehicles, and follow orders (Attack/Defend/Patrol/Move).

This is a hobby project built entirely through AI-assisted development, with human playtesting —
including multiplayer — catching what actually breaks in-game. Treat "it compiles" and "it's
tested" as two different claims; see Workflow below.

## Build, test, deploy

- Build: `./gradlew build` — same as `buildProduction` below (`DebugFlags` off).
- For local testing with verbose trace logs / debug particle markers on: `./gradlew buildDevelop`.
  `./gradlew buildProduction` is the explicit opposite, identical to plain `build`. The flag is
  baked into the jar at build time by the generated `BuildFlags` object (see
  `generateBuildFlags` in `build.gradle.kts`) — it's not something to pass at runtime.
- Tests only: `./gradlew test`
- Compiling (`compileKotlin`) is **not** a testable artifact — a real jar requires `build`.
- Deploy for in-game testing: copy the built jar into the **flat** `~/.minecraft/mods/` folder:
  ```
  cp build/libs/sbwnpc-<version>-mc1.21.1.jar ~/.minecraft/mods/
  ```
  Do NOT put it in a `~/.minecraft/mods/1.21.1/` subfolder — that path isn't read by the
  launcher, it's an unrelated personal archive folder. Remove any stale-version jar from
  `mods/` first so two versions of the same mod don't both load at once.
- This addon depends on a local SuperbWarfare checkout via `includeBuild("SuperbWarfare")` in
  `settings.gradle.kts` (it needs SBW's internal APIs, not just its published jar). `/SuperbWarfare/`
  is gitignored — pulled and maintained separately, not part of this repo.

## Cloud sessions (Claude Code on the web)

- `.claude/hooks/session-start.sh` clones SuperbWarfare (branch `0.8.9.2-1.21` by default, override
  with `SBW_BRANCH`) and runs `./gradlew testClasses`, so a cloud session can build and test. Bump the
  branch when the local SuperbWarfare snapshot moves to a newer release.
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

## Workflow requirements

- **Never push a fix on the strength of "it should work" or "it compiles."** Build, deploy, and
  wait for the user to confirm it works in-game before it leaves the machine.
- **Pushing** needs the user's say-so for that specific push. Permission given for one push doesn't
  carry over to the next, unrelated one, even later in the same conversation — ask again rather
  than assuming it still applies.
- Committing is not pushing. Committing is local and happens as you work; it is the publishing
  step that is gated.
- Prefer several small commits over one large one, split by what each change actually does.
- Commit as the work lands, not in a batch at the end: one feature finished, compiling and passing
  tests, commit it — before the in-game check, not after. If that check shows a commit was wrong,
  fix it with a follow-up commit or rework the unpushed commits locally before pushing. A
  session's worth of unrelated work sitting uncommitted is what forces splitting it after the fact.
- Don't bundle unrelated doc-only changes into a commit unless the user explicitly asked for the
  doc change too.
- Commit messages: concise, and start with a Conventional Commits prefix (`feat:`, `fix:`,
  `docs:`, `refactor:`, `perf:`, `test:`, `chore:`, ...) matching what the commit actually is.
- Branch names say what the branch does: `<type>/<short-kebab-description>`, e.g.
  `feature/add-mortar-barrage`, `fix/medic-heals-enemies`, `perf/cache-cover-search`,
  `docs/architecture-overview`. The type matches the commit prefix it will mostly carry
  (`feature/` for `feat:`, otherwise the same word: `fix/`, `perf/`, `refactor/`, `docs/`,
  `test/`, `chore/`). Lowercase, hyphens between words, a few words at most, no issue numbers or
  dates on their own. Branch off `develop` and open the PR back into `develop`.
- When the user says to slow down ("не торопись") or similar, actually stop and wait — don't
  keep proposing or making the next change.
- Prefer reusing an existing vanilla/NeoForge/SuperbWarfare class or mechanism over inventing a
  new one; check with the user before building a parallel system for something SBW or vanilla
  already does.
- This project stays on NeoForge 1.21.1. A 1.21.6/Fabric port was investigated and shelved —
  SuperbWarfare's own port to that target isn't ready, and this addon leans on Create/Ponder-style
  APIs that block on it.

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
  the rules below, and the history belongs in the commit message.
- Re-read the whole diff before opening the PR, as a reviewer would, and cut whatever isn't needed.

The description:

- Title: a Conventional Commits line, the same style as commit messages.
- Body: what changes for the player or the code, and why, in a few plain sentences. Then how it
  was tested, stated honestly: "built, unit tests pass, not yet tested in game" is a valid answer.
  Add known risks or open questions only if there are any.
- Don't restate the diff file by file, list every commit, or repeat the title.
- No filler: no "This PR introduces...", no "comprehensive", "robust", "seamless" or "enhanced",
  no emoji headings, no summary tables for a three-line change, no "future improvements" nobody
  asked for, no empty template sections.
- A PR that changes gameplay stays a draft until it has been tested in game.

## Code comments and KDoc

Comments are for the next reader of the code, human or AI. Distilled from
[Best practices for writing code comments](https://stackoverflow.blog/2021/12/23/best-practices-for-writing-code-comments/)
and the [KDoc reference](https://kotlinlang.org/docs/kotlin-doc.html):

- Say *why*, not *what*. Don't restate the code; a comment that only repeats it is noise that goes
  stale. If a name or a small extraction would make the comment unnecessary, do that instead.
- If a clear comment is hard to write, the code is probably unclear; consider fixing the code.
- Explain anything unidiomatic or surprising, especially workarounds for vanilla, NeoForge,
  SmartBrainLib or SuperbWarfare behaviour, so nobody "simplifies" it back into the bug.
- When fixing a bug, leave a comment where the fix isn't self-evident, saying what went wrong.
  Commit messages are where the full story goes; the comment is just enough to stop a regression.
- Link the source when code is copied or adapted from somewhere, and link external references
  (a wiki page, an SBW class, a forum thread) where they help the reader most.
- Mark unfinished work with `TODO:` and what's missing, not a bare `TODO`.
- Comments should dispel confusion, not cause it. Delete or fix comments that no longer match the
  code; a stale comment is worse than none.
- Write comments in English, like the rest of the sources.

KDoc (`/** ... */`) goes on every class, object and non-obvious public or `internal` member:

- The first paragraph is a one-sentence summary; details follow after a blank line. Markdown works.
- Refer to code with `[Name]` or `[text][Name]` links (`[SquadManager.get]`, `[Ports.guns]`), not
  backticks, so they resolve and get renamed with the code.
- Prefer describing parameters and the return value in prose. Use block tags when they add
  information: `@param name`, `@return`, `@throws ExceptionClass` (for exceptions a caller should
  expect), `@property name` (constructor properties), `@receiver`, `@see`, `@sample`.
- Mark deprecation with the `@Deprecated` annotation, not a KDoc tag.
- Never put a literal `/*` inside a KDoc comment (see the gotcha below).

## Gotchas worth knowing before you hit them

- **Kotlin block comments nest** (unlike Java/C). A literal `/*` substring inside a `/** ... */`
  KDoc comment — e.g. a glob path like `foo/*.json` — opens a phantom nested comment that
  swallows the rest of the file as "unclosed comment." Don't put `/*` inside a doc comment.
- **`StreamCodec.composite(...)` tops out at 6 fields.** A network payload needing more fields
  must hand-write its codec via `StreamCodec.of(encoder, decoder)`.
- **Raw Ctrl-key state isn't synced to the server** the way sneaking is. Client-side modifier-key
  handling for tool interactions goes through `InputEvent.InteractionKeyMappingTriggered` +
  `Screen.hasControlDown()`, not a server-side player-state check.
- **Mutable-collection accessors (e.g. `SquadSelection.looseOf(...)`) return a live reference**,
  not a copy. Storing that reference as a "before" snapshot to diff against "after" makes both
  sides the same object — the diff silently does nothing. Copy before storing if you need a
  real snapshot.
- **Vanilla `Entity.setGlowingTag()` is globally synced** — every nearby player sees it, not just
  the one who triggered it. For a "this is my selection" cue, use targeted particles
  (`ServerLevel.sendParticles(ServerPlayer, ...)`) instead.

## Architecture pointers

Full map of packages, wiring and where to add things: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
The short version:

- AI behaviors live under `entity/ai/*Behaviour.kt`, written against SmartBrainLib's
  behavior-tree framework (`ExtendedBehaviour`, `BrainActivityGroup`, `MemoryModuleType`) — not
  vanilla `Goal`/`GoalSelector`.
- Faction/hostility runs on vanilla scoreboard teams (`SquadTeams`), keyed per faction
  (`sbwnpc_<faction>`). Players are never put on a team directly — their chosen faction lives in
  `PlayerFactionRegistry`, a `SavedData`.
- Client↔server messages are `CustomPacketPayload`s registered under `network/`, via
  `RegisterPayloadHandlersEvent`.
- Squad state (`SquadManager`) and player faction picks (`PlayerFactionRegistry`) are both
  `SavedData` — persisted per world, not per session.
- Only `integration/sbw/` (and the mixins) may import SuperbWarfare; everything else goes through
  the ports in `domain/port/`. `SbwBoundaryTest` enforces it.
- Runtime-only static state must be cleared in `ServerLifecycle.onServerStopped`, or it leaks into
  the next world.
