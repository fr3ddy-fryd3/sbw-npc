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

## Workflow requirements

- **Never commit or push a fix on the strength of "it should work" or "it compiles."** Build,
  deploy, and wait for the user to confirm it works in-game before committing.
- **Pushing** needs the user's say-so for that specific push. Permission given for one push doesn't
  carry over to the next, unrelated one, even later in the same conversation — ask again rather
  than assuming it still applies.
- Committing is not pushing. Committing finished, verified work is fine; it is the publishing step
  that is gated.
- Prefer several small commits over one large one, split by what each change actually does.
- Commit as the work lands, not in a batch at the end: one feature finished, the user confirms it
  works in-game, commit it. A session's worth of unrelated work sitting uncommitted is what forces
  the batching in the first place.
- Don't bundle unrelated doc-only changes into a commit unless the user explicitly asked for the
  doc change too.
- Commit messages: concise, and start with a Conventional Commits prefix (`feat:`, `fix:`,
  `docs:`, `refactor:`, `perf:`, `test:`, `chore:`, ...) matching what the commit actually is.
- When the user says to slow down ("не торопись") or similar, actually stop and wait — don't
  keep proposing or making the next change.
- Prefer reusing an existing vanilla/NeoForge/SuperbWarfare class or mechanism over inventing a
  new one; check with the user before building a parallel system for something SBW or vanilla
  already does.
- This project stays on NeoForge 1.21.1. A 1.21.6/Fabric port was investigated and shelved —
  SuperbWarfare's own port to that target isn't ready, and this addon leans on Create/Ponder-style
  APIs that block on it.

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
