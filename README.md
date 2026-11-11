<div align="center">

# [SBW] NPC Squads

**An NPC squad addon for [SuperbWarfare](https://github.com/Mercurows/SuperbWarfare)** — recruit, deploy,
and command your own AI-driven infantry, mortar, tank, drone, and helicopter squads.

![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1-orange)
![Fabric](https://img.shields.io/badge/Fabric-1.21.1-lightgrey)
![Forge](https://img.shields.io/badge/Forge-1.20.1-red)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)
![Status](https://img.shields.io/badge/status-alpha-yellow)

</div>

---

## What is this

[SBW] NPC Squads lets you recruit NPCs with a single tool, form them into squads, and command them like
a small AI-controlled army: riflemen, shotgunners, medics, snipers, machine gunners, grenadiers, scouts, mortar crews,
tank and helicopter crews, and kamikaze-drone operators. Squads take cover, dig in under fire, avoid shooting
their own side, relay spotted enemies to the rest of their faction, ride and crew vehicles, and
follow orders — Attack, Defend, Patrol, Move, Follow — from a quick-command HUD or by pointing and
clicking. Supply points keep them equipped, boats carry them across water, and radio reports tell
you when they make contact, lose men, need ammunition, or reach their objective.

## Alpha

1.0.0-alpha2 is the second release for all three loaders. It is an alpha: expect rough edges in the AI,
and keep backups of worlds you care about. Bug reports are welcome on the [Issues](../../issues) page.

## Requirements

| | NeoForge | Fabric | Forge |
|---|---|---|---|
| Minecraft | 1.21.1 | 1.21.1 | 1.20.1 |
| Loader | NeoForge 21.1.203 or newer | Fabric Loader 0.17.2 or newer | Forge 47 or newer |
| Kotlin | Kotlin for Forge 5.8.0 or newer | Fabric Language Kotlin | Kotlin for Forge 4.11 |
| SuperbWarfare | [SuperbWarfare](https://github.com/Mercurows/SuperbWarfare) 0.8.9.1 or newer | [SuperbWarfare ReFabricated](https://github.com/CrawKatt/SuperbWarfareReFabricated) 0.8.9 or newer | SuperbWarfare 0.8.9 or newer for 1.20.1 |
| SmartBrainLib | 1.16.11 or newer | 1.16.11 or newer | 1.15 or newer |

Optional:

- the SBW Drone Warfare addon, for the drone operator class to use its FPV drone model;
- [JourneyMap](https://www.curseforge.com/minecraft/mc-mods/journeymap) 6.x: your squads, their
  objectives and the enemies your side has spotted on the map, and orders given from it. Only tried
  on NeoForge so far.

Worlds don't carry over between Minecraft 1.20.1 and 1.21.1.

## Installation

1. Install the loader, its Kotlin library, SuperbWarfare and SmartBrainLib first.
2. Grab the jar for your loader from the [Releases](../../releases) page: the file name ends in
   `-neoforge`, `-fabric` or `-forge`.
3. Replace the previous NPC Squads jar in your `mods/` folder with the new one.

## Key conflicts

A few mods often installed alongside bind the same keys as SuperbWarfare, and both actions fire on
one press. Rebind the other mod's key under *Options → Controls → Key Binds*: click the binding
and press the new combination, `Ctrl` included.

| Key | SuperbWarfare | Clashes with | Suggested |
|---|---|---|---|
| `R` | Reload | Iris — *Reload Shaders* | `Ctrl + R` |
| `N` | Fire mode | JourneyMap — *Waypoint Manager* | `Ctrl + N` (the list is also a button on the fullscreen map) |

## How this mod was made

Developer guides: [Project structure / Карта проекта](docs/PROJECT-STRUCTURE.md) ·
[Squad tactics architecture / Архитектура тактики](docs/TACTICS-ARCHITECTURE.md).

This is a hobby project, built purely for fun. The entire codebase was written by an AI (Claude
Code and Codex) — architecture, features, bug fixes, all of it — while the meatbags did the actual
playtesting, including multiplayer sessions, and sent back bug reports on whatever broke.

Pull requests and issues are welcome and will be looked at whenever there's free time — no
guaranteed response time.

## Features

- **8 factions** with distinct uniforms and colours; deploy friendly or hostile squads.
- **Combat AI** that plans how each squad fights: bounding overwatch, flanks, assaults by halves
  under covering fire, smoke-covered retreats, and scouts sent to look at a shot from out of sight.
  Squads take cover, react to suppression, avoid friendly fire and share contacts.
- **Spotting takes time**, by distance, light, weather, cover and experience; men grow wary after a
  contact and look around.
- **Joint operations** — several squads on one point attack it together, each from its own sector,
  and call off a beaten attack. A second group goes round the flank, takes the high ground or
  envelops, whichever suits the ground, and waits for the mortars to soften the objective first.
- **Recon squads** — three fast scouts creep to an observation post short of the objective, report
  what they see in chat and hold their fire. Attackers in the same operation wait for the report.
- **Fire teams** — larger squads split into lasting teams, each with its machine gunner and
  assault man.
- **Soldiers with a record** — each one has a persistent name and experience, and earns his rank.
- **Ground and water transport** — squads drive, crew mounted guns, plan routes around terrain and
  cross water by boat. Amphibious vehicles travel between land and water.
- **Drone operators** attack with kamikaze drones; other NPCs shoot down or take cover from them.
- **Helicopters** — Mi-28 gunships and AH-6 transports with NPC pilots and gunners. Aircraft remain
  visible out to 512 blocks. Surviving crew can take over if the pilot is killed.
- **Logistics and reports** — Barracks replace losses, Supply replenishes equipment, and squads
  report contacts, casualties and ammunition shortages to their owner.

## Where everything is

| Thing | Where to get it | What it does |
|---|---|---|
| **Squad Command Tool** (`sbwnpc:squad_tool`) | Creative, *Superb Warfare Items* tab — or `/give @s sbwnpc:squad_tool` | Deploys NPCs and commands squads. No recipe: in survival you are handed one on joining and on respawning whenever you have none. |
| **Barracks** (`sbwnpc:barracks`) | Same tab — or `/give @s sbwnpc:barracks` | A placed block that garrisons a squad and keeps it at strength. |
| **Supply Point** (`sbwnpc:supply`) | Same tab — or `/give @s sbwnpc:supply` | Automatically resupplies friendly NPCs within 8 blocks; right-click for a player kit or respawn point. |
| **Quick-command HUD** | Key `Z`, rebindable under controls category *SBW NPC Squads* | Pick a squad and an order without opening a menu. |

On first use the tool asks you to pick a faction once. In survival you recruit and deploy only that
faction, by the tool and by a Barracks. In creative you can deploy any of the eight, including
hostile ones to fight against, and set which faction a Supply serves.

## Using the tool

Two modes, switched with **Ctrl + right-click on air**. The current one is on the item tooltip.

**Recruit mode — what gets deployed**

- **Right-click** deploys where you look, up to 64 blocks away, on a block or at a distant spot. Cover in the
  way takes the squad instead of the ground behind it. The squad is lined up abreast and faces you. A column
  of particles, visible only to you, marks the spot while the tool is in your hand.
- **Shift + right-click** opens the deploy config: preset, class (Single only), faction, and the
  vehicle or airframe where the preset has one.
- Presets: `Single`, `5: Riflemen`, `7: Standard`, `16: Large`, `32: Company`, `Recon`, `Mortar Crew`, `Tank Crew`
  (ZTZ-99A / T-90A / M1A2), `Drone Team`, `Heli Crew` — Mi-28 gunship or AH-6 transport, and the
  airframe decides which crew comes with it. `5` and `7` can bring a LAV-25 / LAV-150 / BMP-2.
- New soldiers start as `RECRUIT`, with a persistent name and personal experience. Ranks progress
  `RECRUIT → REGULAR → VETERAN → ELITE`, at 0 / 300 / 1200 / 3500 cumulative XP by default
  (`regularExperience`, `veteranExperience`, `eliteExperience` in the server config).
  Hostile damage, kill contributions, combat treatment and useful combat participation earn XP.
  Promotion improves rank statistics while preserving current health, weapons and ammunition.
  Existing soldiers keep their rank; replacements start from zero.
- Anything bigger than a single NPC is formed into a squad automatically, holding where it landed.

**Command mode — what they do**

- **Right-click an NPC** selects it; particles above individually selected NPCs mark the selection.
  One that is already in a squad selects the whole squad.
  Shift + right-click clears the selection.
- **Right-click air** opens the squad screen.
- **Right-click a block** with a squad selected sets that squad's objective.
- **Right-click a hostile** focuses the selected squad on it — hunted under Attack, guarded
  against under Defend.

## The squad screen

One row per squad, scrollable, with the footer buttons under the list:

- **Squad name** opens a live detail page: order, tactical state, objective, and each loaded soldier's
  name, role, rank, experience, health and primary/pistol ammunition. Unloaded data is marked unavailable.
- In that page, **Order** cycles available orders; **Objective** arms the next block click;
  **Focus** arms an entity click.
- **Merge** joins the squad into a selected infantry squad with the same owner and faction;
  neither may be assigned to barracks. The destination keeps its identity, order and objective.
  Soldiers keep their UUIDs, names, experience, rank and kit, including across chunk unloads.
- **R** renames · **D** disbands (the NPCs stay, the squad doesn't) · **DEL** deletes the squad
  and everything in it, vehicles included.
- **Routes** opens patrol routes: add one, right-click blocks to drop waypoints, finish, then
  assign it to a squad. A squad with a route walks it under Patrol.
- **Reports** switches your squads' chat radio reports on or off; the setting is saved per world.

There is no limit on how many squads you run. The HUD's number keys reach the first nine;
disbanding one moves the rest up into the freed numbers.

## Orders

| Order | What it means | Who can be given it |
|---|---|---|
| **Defend** | Hold near the objective, don't chase far | Everyone except tank crews |
| **Patrol** | Roam round the objective (30 to 50 blocks, by squad size), or walk the assigned route | Infantry |
| **Attack** | Advance on the objective; AH-6 circles it for its bench gunners | Everyone except tank crews |
| **Move** | Travel to the objective, then hold or land | Infantry, tank crews, helicopters |
| **Barrage** | Shell a 40-block area around the objective instead of one point | Mortar crews |
| **Retreat** | Fall back to the objective, then hold; helicopters land there | Everyone |
| **Follow** | Stay with you, spread out, and fight on the way; ride in the vehicle you sit in. One squad follows a player at a time, and it holds where it stands if you die or leave | Infantry |

Tank crews take **Move** and **Retreat** — they fight from the tank by themselves. A Mi-28 gunship takes
Attack / Defend / Move / Retreat and attacks from a standoff hover. An AH-6 uses Defend to patrol
low with its door gunners, Move and Retreat to carry the squad and land, and Attack to circle the
objective with its gunners facing inward. AH-6 Attack stays active until another order or a forced
withdrawal. JourneyMap menus offer the orders available to the selected squad types.

In enemy contact, defenders and patrols keep a useful firing position while the target is within
twice their class's normal combat range. A target farther away can prompt an approach of at most
24 blocks from the original post, then they stop and fire. They do not chase a target merely to
restore their preferred distance. Snipers, medics and machine gunners use rear positions during
combat; without an enemy present they travel to the ordered objective with everyone else.

## The Barracks

Right-click a Barracks you placed, choose its composition, then press **Apply recruitment**.
It creates an empty squad and recruits one NPC every `recruitIntervalSeconds` (10 seconds by
default, in the world's `serverconfig/sbwnpc-server.toml`). The first recruit also waits a full
interval: seven soldiers take at least 70 seconds. Initial recruits and replacements share one
queue across every squad assigned to that Barracks. Replacements have priority, with one initial
recruit admitted after three replacements when both are waiting.

The **Queue** button shows the actual release order, next recruit, time remaining, and any reason
production is waiting. The queue and countdown survive saves; unloading pauses recruitment, and
reloading never releases a catch-up wave. Unloaded soldiers keep their places. Older saves wait
for unknown member roles to load before recruiting replacements. No safe spawn space means the
request waits. Drone operators nearby still get their drones replenished independently.

Applying new settings preserves living soldiers and changes future recruitment. Surplus soldiers
stay until they die; their retired places are not refilled. Choosing another faction releases the
old squad to manual control and queues a new one on the selected side. Only the player who placed
the Barracks can configure it. Disbanding or deleting its garrison cancels that recruitment order.
Support vehicles and mortars deploy once the initial crew is assembled; replacements do not
generate additional vehicles.

An assigned Barracks keeps ticking away from players. Deleting or disbanding its garrison from
the squad screen stops production until you configure it again.

Destroying the Barracks leaves the squad alive but stops reinforcement.

## Supply points

A Supply serves the faction of the player who placed it and that faction's allies. Friendly NPCs
within 8 blocks automatically receive their issued ammunition, grenades, medical kit, mortar
shells and drones. Between fights, NPCs low on ammunition return to the nearest friendly Supply;
when nearly empty, they can withdraw there while fighting. Mortar loaders carry 30 shells and
fetch more from Supply when they run out.

Right-click a friendly Supply to choose a class kit and one of that class's weapons. Kits provide
the gun, ammunition, class equipment and your side's helmet and vest, topping up what you are
missing. The machine gunner kit includes an RPG with TBG rockets. With Drone Warfare installed,
the drone operator kit uses its FPV drones and goggles, plus a monitor; otherwise it uses SBW drones.

**Respawn here** makes it your spawn point while the block remains friendly and has room above
it. A bed or respawn anchor replaces that choice. If the Supply is destroyed, inaccessible or no
longer friendly, you return to world spawn. Supply stock is currently unlimited. A Supply placed
before it had logistics support may need to be broken and placed again.

## Worth knowing

- Several squads ordered onto the same point fight it as one operation, each from its own side.
  Right-clicking an operation's marker on the JourneyMap map lets the selected squads join it.
- Squads take cover, dig in when badly hurt, and won't fire through a squadmate — or through a
  parked vehicle.
- A squad with a distant objective commandeers a vehicle nearby and drives. You can ride along in
  a free seat without bumping the NPC driver out.
- They only see what is in front of them, so they can be flanked — but a shot at them, or a
  contact their own side calls in, turns them around.
- NPCs can see through glass but will not fire through it. Three blocks
  of grass or fern along the sightline hide a target. Enemy footsteps are heard within 4 blocks
  when walking, 8 when running and 3 when landing; crouching is silent.
- Every NPC has one medical kit and uses it on itself when wounded in cover; Supply replenishes it.
- Shelled from a gun they can't see, NPCs run for a roof nearby; shells landing close make them
  run and look about.
- Half a squad at most leaves for a Supply at a time; the rest hold the line.
- Machine gunners carry a launcher on the side for anything riding a vehicle; infantry stays the
  machine gun's job.
- NPCs throw grenades at covered enemies or when pinned, lobbing them over cover when needed, one
  thrower per squad at a time, checking reach, obstacles, vehicle hulls and nearby allies.
  Passengers don't throw.
- NPCs open wooden doors and ladder or scaffolding trapdoors, and climb ladders and scaffolding.
- Mobs that hunt players hunt NPCs too, and NPCs fight back.
- Mortar crews only shell what their own side has actually seen. Out of reach of the target, they
  break the mortar down, carry it closer and set it back up.
- A killed NPC drops the weapon in its hands with a 3.75% chance (with only its loaded magazine), each
  piece of armour with a 1.25% chance, a box of ammunition for its gun with a 28.125% chance, and one of
  the grenades it had left with a 1.25% chance, whoever killed it.
  Ammunition without a box item, such as heavy rounds and rockets, drops as individual items.

## Skins

![Skin preview](docs/skins/preview.png)

## License

[GNU GPL v3.0](LICENSE) — see the `LICENSE` file for the full text.
