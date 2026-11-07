# Release validation

Pull requests and pushes to `develop` run `CI`: common and NeoForge checks, three dedicated-server GameTests, a production build, and release artifact verification. The Fabric and Forge jobs build the production jar of their loader and verify it the same way. Version tags run the same checks before the `Release` workflow publishes the jars of all three loaders. A tag must match `mod_version` in `gradle.properties`, and so must the version in `neoforge.mods.toml` and the Forge `mods.toml` (the verifier checks it); `fabric.mod.json` takes it from `mod_version`.

A Gradle run configures one loader and stages only its jar into `build/libs`, so a release builds the loaders one after another: `./gradlew build`, then `./gradlew -Ploader=fabric build` and `./gradlew -Ploader=forge build`, each followed by `python3 scripts/verify-release-artifact.py [--loader <name>]` before the next build replaces `build/libs`. The workflow collects the verified jars in `dist/` and publishes them.

`scripts/verify-release-artifact.py` checks, for one loader, that its profile in `gradle/sbw/` is `enabled`, that `build/libs` contains exactly that loader's jar (`sbwnpc-<version>-mc<minecraft>-<loader>.jar`), and that the jar has the loader's metadata (mod ID and version), mixin config (and refmap on Forge), key resources, the loot table folder of its Minecraft version and no resources of the other one, and `DEBUG_ENABLED = false`. It also checks the SBW build input report (`build/reports/sbw/input.txt`, written by the `verifySbwInput` task every loader module runs) against the pinned profile. The profile status is `enabled` for the loaders a release publishes and `preview` for one that is only built for development.

Not covered by CI: the GameTests and the dedicated-server smoke run only in the NeoForge module, so nothing starts a Fabric or Forge server in CI. The human gate below is the only runtime check of those jars.

## Human gate before a release tag

CI cannot confirm gameplay. On the candidate jar of each loader (NeoForge and Fabric on 1.21.1, Forge on 1.20.1), a person should test in singleplayer and multiplayer:

1. Recruit and deploy a squad; issue Attack, Defend, Patrol, and Move orders.
2. Confirm combat, weapon use, and at least one vehicle interaction with SuperbWarfare installed.
3. Save and reopen the world; check squad ownership, orders, and persistence.
4. Check the map integration with JourneyMap installed, and repeat a basic command without it.

Record the game version, mod versions, scenarios, and results in the release PR or issue before tagging. A green workflow is a build gate; the release decision still needs these playtest results.
