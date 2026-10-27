# Cloud sessions: network allowlist

Claude Code on the web builds the project through `.claude/hooks/session-start.sh`. The cloud
environment's network allowlist must include these domains, or the build fails while resolving
dependencies:

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
- Only for `scripts/setup.sh --refabricated` and `-Ploader=fabric`: `maven.fabricmc.net`,
  `maven.terraformersmc.com`, `maven.ladysnake.org`
- Only for `-Ploader=forge`: `maven.minecraftforge.net`
- Optional, for Parchment parameter names on Fabric and Forge: `ldtteam.jfrog.io`
