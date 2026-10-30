# Reading and extending the code

Where the mechanics live is in the [project map](PROJECT-STRUCTURE.md); how tactics work is in
[AI-RUNTIME.md](AI-RUNTIME.md) and [TACTICS-ARCHITECTURE.md](TACTICS-ARCHITECTURE.md). This document
covers formatting and how logic is split. The base style follows the
[official Kotlin conventions](https://kotlinlang.org/docs/coding-conventions.html); the automatic
check uses [ktlint 1.8.0](https://ktlint.github.io/ktlint/1.8.0/).

## Formatting

Before a commit:

```bash
./gradlew formatKotlin checkKotlinStyle test --console=plain
git diff --check
```

`formatKotlin` rewrites Kotlin in the main code and tests. `checkKotlinStyle` checks the same files
and is part of Gradle `check`. `detekt` (also in `check`) looks for code smells: methods that are too
complex, unnamed thresholds, unused parameters. It does not check formatting. Its settings are in
[config/detekt/detekt.yml](../config/detekt/detekt.yml); older findings are recorded in each
module's `detekt-baseline.xml`, and new ones are fixed rather than added there. Formatter settings
are in [.editorconfig](../.editorconfig). The formatter dependency sits in its own `kotlinStyle`
configuration and does not end up in the mod.

A multi-line condition gets braces. Each stage of a method is separated by a blank line. A
constructor with several boolean fields uses named arguments: `ready = ready`,
`canFire = canFire`, `suppressed = npc.isSuppressed()`.

Don't remove the arrow in lambdas like `{ -> ... }` in NeoForge registration. It states that there
are no parameters and helps pick the `Supplier` overload when a `Function` one exists too. The
formatter's `function-literal` rule is disabled for this reason. Long diagnostic strings and
existing wildcard imports are also excluded from the mandatory checks; that is no reason to squeeze
conditions onto one line.

## Splitting logic

An update method should show the order of its steps. The computation and execution of a step live
in methods named for what they mean: `updateAim`, `updateFiringLane`, `recoverIfStuck`,
`scoreCandidate`. Split methods by responsibility; a small sequential algorithm can stay whole.

A complex condition gets a name that explains its meaning in game: `waitingShooterAvailable`,
`hasHeightAdvantage`, `canFireNow`. Names like `a`, `b`, `t` are fine for a short formula; for a
target, member or route use `target`, `member`, `path`.

A threshold's name says what it is for and its unit. For example `BLOCKED_SIGHT_TICKS` is in ticks,
`CONTACT_RADIUS` in blocks. Equal numbers with different meanings get different names. When
extracting a formula, keep the order of arithmetic and the comparison boundaries.

Checks that touch the world, read navigation, spend budget, change state or use random numbers run
in their original order. Don't compute every condition up front if short-circuiting used to skip
some calls. Leaving a step shows plainly whether the tick goes on.

## Where to extend tactics

| Change | Place |
|---|---|
| A way of carrying out an order at the operation, squad, team or NPC level | HTN methods in `TacticalDomain` (`common/.../ai/tactics`) |
| A squad's strategy (advance, hold, gain height, withdraw) | A squad HTN method per strategy, scored by `SquadManners` |
| Attack phases and their transitions | `AttackPhase`; the history and roles of a running attack in `AttackProgress` |
| A member's duty and post in an operation | `OperationTactics`, `OperationFront` |
| An attack's strength and when it holds or withdraws | `CombatStrength` |
| Collecting data from the world | `TacticalSnapshotCollector` |
| Gathering observations and applying decisions in the world | `ai/integration/NativeTactics`, `NativePositionAction` |
| Constraints and scoring of a position | `TacticalPositionPolicy` |
| Enumerating points and checking a route | Stages in `TacticalPositions` |

A decision belongs in a pure function or object in `common/.../ai/tactics`, with unit tests;
`NativeTactics` gathers observations and applies results. Decisions still made inside
`NativeTactics` are being moved out (#45).

## Comments, diagnostics and tests

A comment explains the reason for a constraint, a contract or the units of a formula. Method names
describe the action. The history of a discussion or a long story of an old bug should not hide the
current implementation.

### Code comments and KDoc

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
- Never put a literal `/*` inside a KDoc comment (see the gotcha in [AGENTS.md](../AGENTS.md)).

When refactoring, keep event reasons and log fields: they connect observed behaviour with the rule,
phase and task. Check whether logging is on before collecting expensive diagnostics.

Automatic tests check a scene's outcome, priorities, condition boundaries and the release of
resources. Passing compilation and tests confirms only those checks. Reachable routes and how well
tactics work are also checked in game.
