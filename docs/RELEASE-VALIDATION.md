# Release validation

Pull requests and pushes to `develop` run `CI`: common and NeoForge checks, three dedicated-server GameTests, a production build, and release artifact verification. Version tags run the same checks before the `Release` workflow publishes the JAR. A tag must match `mod_version` in `gradle.properties`.

`scripts/verify-release-artifact.py` checks that `build/libs` contains exactly the JAR for the enabled NeoForge profile. Fabric and Forge profiles are audit-only and must not produce staged release JARs. The script checks the JAR's metadata, key resources and production debug flag, plus the SBW build input report against the pinned profile. If another loader becomes enabled, extend the build and validator for that loader before releasing it.

## Human gate before a release tag

CI cannot confirm gameplay. On the candidate build, a person should test in singleplayer and multiplayer:

1. Recruit and deploy a squad; issue Attack, Defend, Patrol, and Move orders.
2. Confirm combat, weapon use, and at least one vehicle interaction with SuperbWarfare installed.
3. Save and reopen the world; check squad ownership, orders, and persistence.
4. Check the map integration with JourneyMap installed, and repeat a basic command without it.

Record the game version, mod versions, scenarios, and results in the release PR or issue before tagging. A green workflow is a build gate; the release decision still needs these playtest results.
