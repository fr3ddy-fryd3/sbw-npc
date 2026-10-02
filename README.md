<div align="center">

# [SBW] NPC Squads

**An NPC squad addon for [SuperbWarfare](https://github.com/Mercurows/SuperbWarfare)** — recruit, deploy,
and command your own AI-driven infantry, mortar, tank, and drone squads.

![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1-orange)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetbrains-7F52FF?logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)
![Status](https://img.shields.io/badge/status-active%20dev-yellow)

</div>

---

## What is this

[SBW] NPC Squads lets you recruit NPCs with a single tool, form them into squads, and command them like
a small AI-controlled army: riflemen, medics, snipers, machine gunners, grenadiers, mortar crews,
tank crews, and kamikaze-drone operators. Squads take cover, dig in under fire, avoid shooting
their own side, relay spotted enemies to the rest of their faction, ride and crew vehicles, and
follow orders — Attack, Defend, Patrol, Move — from a quick-command HUD or by pointing and
clicking.

## Version 1.0.0 design

[PLAN-1.0.0.md](PLAN-1.0.0.md) defines the next version: physical NPC commanders with delegated
authority and succession, sequential barracks recruitment, earned ranks, engineering squads,
territorial Supply points, and operations. These systems are being designed on `feature/1.0.0`;
`develop` remains on `0.6.0-dev` for playtesting and fixes. The recruitment queue is implemented
on `feature/1.0.0`; the remaining systems are planned features, not part of
the current release.

## Requirements

- Minecraft 1.21.1 + NeoForge
- Kotlin for Forge (NeoForge build) — this mod is written in Kotlin and loads through it
- [SuperbWarfare](https://github.com/Mercurows/SuperbWarfare) — this addon builds directly on its
  weapons, vehicles, and combat systems and won't do anything without it
- SmartBrainLib — the behavior-tree AI framework every NPC's combat/movement logic runs on
- Optional: the SBW Drone Warfare addon, for the drone operator class to use its FPV drone model
- Optional: [JourneyMap](https://www.curseforge.com/minecraft/mc-mods/journeymap) 6.x — your squads,
  their objectives and the enemies your side has spotted on the map, and orders given from it
- Optional: [Physics Mod](https://www.curseforge.com/minecraft/mc-mods/physics-mod) — not needed by
  this addon at all, but ragdolls, debris and shattering blocks make a firefight feel a lot more real

## Installation

1. Install NeoForge, Kotlin for Forge, SuperbWarfare, and SmartBrainLib first.
2. Grab the latest jar from the [Releases](../../releases) page.
3. Drop it into your `mods/` folder.

NPC guns use cached static third-person models within 64 blocks (4 chunks) of the camera.
Their rendering bypasses GeckoLib and uses SBW's simplified weapon meshes independently of its
`enable_gun_lod` setting. Players keep SBW's normal weapon renderer.

## Key conflicts

A few mods often installed alongside bind the same keys as SuperbWarfare, and both actions fire on
one press. Rebind the other mod's key under *Options → Controls → Key Binds*: click the binding
and press the new combination, `Ctrl` included.

| Key | SuperbWarfare | Clashes with | Suggested |
|---|---|---|---|
| `R` | Reload | Iris — *Reload Shaders* | `Ctrl + R` |
| `N` | Fire mode | JourneyMap — *Waypoint Manager* | `Ctrl + N` (the list is also a button on the fullscreen map) |

## How this mod was made

This is a hobby project, built purely for fun. The entire codebase was written by an AI (Claude
Code) — architecture, features, bug fixes, all of it — while the meatbags did the actual
playtesting, including multiplayer sessions, and sent back bug reports on whatever broke.

Pull requests and issues are welcome and will be looked at whenever there's free time — no
guaranteed response time.

## Features

- **One tool, two modes** — Recruit mode opens a GUI to pick class, rank, faction and squad
  preset and deploy it; Command mode selects squads and issues orders. Ctrl+right-click switches
  between them.
- **Squad presets** — Single, 5 (riflemen + medic), 7 (riflemen, sniper, machine gunner, medic),
  16 (large mixed squad), 32 (a company: twice the 16), Mortar Crew, Tank Crew, Drone Team, Heli
  Crew. The infantry presets deploy standing in a square formation, ready to move. 5 and 7 can
  deploy with a support vehicle; the tank crew preset lets you pick the model (ZTZ-99A / T-90A / M1A2), and the
  heli crew the airframe (Mi-28 gunship or AH-6 transport).
- **8 factions**, each with its own uniform, team, and color — pick freely, including OPFOR
  test squads.
- **Combat AI** — a 150° field of view you can flank them out of, cover-seeking, suppression,
  digging in as a last resort, partial-cover firing positions, friendly-fire avoidance modeled on
  the actual firing cone (not just a straight line), and faction-wide relayed contact awareness.
- **Vehicles** — NPC crews drive, escort, and gun for their squad; a squad far from its objective
  will commandeer nearby vehicles on its own. Players can now ride along in a seat next to an
  allied NPC driver instead of bumping them out.
- **Drone operators** — fly a kamikaze FPV drone at a spotted target (uses the SBW Drone Warfare
  addon's drone if installed, falls back to a stock SBW drone otherwise); other NPCs will shoot
  down or take cover from a hostile drone and alert the squad.
- **Helicopters** — NPC pilots fly a Mi-28 gunship or an AH-6 that carries the squad, takes off,
  picks a landing zone and puts them down. Kill the pilot and the crew has a few seconds to take
  the controls before the aircraft comes down with it.
- **Barracks** — a placeable garrison point, configured like the deploy tool, that deploys a squad
  and restocks its losses over time.
- **Quick-command HUD** — a lightweight panel (default key `Z`) to pick a squad and an order
  without opening a menu.

## Where everything is

| Thing | Where to get it | What it does |
|---|---|---|
| **Squad Command Tool** (`sbwnpc:squad_tool`) | Creative, *Superb Warfare Items* tab — or `/give @s sbwnpc:squad_tool` | Deploys NPCs and commands squads. No recipe: in survival you are handed one on joining and on respawning whenever you have none. |
| **Barracks** (`sbwnpc:barracks`) | Same tab — or `/give @s sbwnpc:barracks` | A placed block that garrisons a squad and keeps it at strength. |
| **Supply Point** (`sbwnpc:supply`) | Same tab — or `/give @s sbwnpc:supply` | Groundwork for squad logistics. Places, breaks, does nothing else yet. |
| **Quick-command HUD** | Key `Z`, rebindable under controls category *SBW NPC Squads* | Pick a squad and an order without opening a menu. |

On first use the tool asks you to pick a faction once. That is only your own default — you can
still deploy any of the eight afterwards, including hostile ones to fight against.

## Using the tool

Two modes, switched with **Ctrl + right-click on air**. The current one is on the item tooltip.

**Recruit mode — what gets deployed**

- **Right-click air** opens the deploy config: preset, class (Single only), rank, faction, and the
  vehicle or airframe where the preset has one.
- **Right-click a block** deploys it there, lined up abreast and facing you.
- Presets: `Single`, `5: Riflemen`, `7: Standard`, `16: Large`, `32: Company`, `Mortar Crew`, `Tank Crew`
  (ZTZ-99A / T-90A / M1A2), `Drone Team`, `Heli Crew` — Mi-28 gunship or AH-6 transport, and the
  airframe decides which crew comes with it. `5` and `7` can bring a LAV-25 / LAV-150 / BMP-2.
- Ranks go `RECRUIT → REGULAR → VETERAN → ELITE`: more health, tighter spread, quicker reactions.
- Anything bigger than a single NPC is formed into a squad automatically, holding where it landed.

**Command mode — what they do**

- **Right-click an NPC** selects it; one that is already in a squad selects the whole squad.
  Shift + right-click clears the selection.
- **Right-click air** opens the squad screen.
- **Right-click a block** with a squad selected sets that squad's objective.
- **Right-click a hostile** focuses the selected squad on it — hunted under Attack, guarded
  against under Defend.

## The squad screen

One row per squad, scrollable, with the footer buttons under the list:

- **Order** cycles through the orders that squad can actually carry out.
- **Objective** arms a click: the next block you right-click is where the squad works from.
- **Focus** arms the same thing on an entity instead.
- **R** renames · **X** disbands (the NPCs stay, the squad doesn't) · **DEL** deletes the squad
  and everything in it, vehicles included.
- **Routes** opens patrol routes: add one, right-click blocks to drop waypoints, finish, then
  assign it to a squad. A squad with a route walks it under Patrol.

There is no limit on how many squads you run. The HUD's number keys reach the first nine;
disbanding one moves the rest up into the freed numbers.

## Orders

| Order | What it means | Who can be given it |
|---|---|---|
| **Defend** | Hold near the objective, don't chase far | Everyone except tank crews |
| **Patrol** | Wander the area, or walk the assigned route | Infantry |
| **Attack** | Advance on the objective, fight through what's in the way | Everyone except transports and tank crews |
| **Move** | Walk there calmly, then hold | Everyone |
| **Barrage** | Shell a 40-block area around the objective instead of one point | Mortar crews |

Tank crews only take **Move** — they fight from the tank by themselves. A Mi-28 gunship takes
Attack / Defend / Move and holds a standoff hover 20 blocks off its target; an AH-6 transport
takes Defend (patrols low with its door gunners, and calls contacts in to the whole faction) and
Move (flies the squad there and lands).

## The Barracks

Right-click a Barracks you placed, choose its composition, then press **Apply recruitment**.
It creates an empty squad and recruits one NPC every `recruitIntervalSeconds` (30 seconds by
default, in the world's `serverconfig/sbwnpc-server.toml`). The first recruit also waits a full
interval: seven soldiers take at least 210 seconds. Initial recruits and replacements share one
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

It is an ordinary block otherwise: mine it or blow it up and it's gone. The squad stays where it
is, with nobody left to replace its losses.

## Worth knowing

- Squads take cover, dig in when badly hurt, and won't fire through a squadmate — or through a
  parked vehicle.
- A squad with a distant objective commandeers a vehicle nearby and drives. You can ride along in
  a free seat without bumping the NPC driver out.
- They only see what is in front of them, so they can be flanked — but a shot at them, or a
  contact their own side calls in, turns them around.
- Machine gunners carry a launcher on the side for anything riding a vehicle; infantry stays the
  machine gun's job.
- Grenades are thrown to flush a target that will not come out of cover and to answer fire that has
  the squad pinned, rationed so an ambush draws an answer rather than a volley.
- Mortar crews only shell what their own side has actually seen. Out of reach of the target, they
  break the mortar down, carry it closer and set it back up.
- A killed NPC drops the weapon in its hands with a 3.75% chance (with only its loaded magazine), each
  piece of armour with a 1.25% chance, a box of ammunition for its gun with a 12.5% chance, and one of
  the grenades it had left with a 1.25% chance, whoever killed it.

## Skins

All eight faction skins were redesigned with original military uniforms while keeping each mob's
head pixel-identical, so they stay instantly recognizable. See [`docs/skins/`](docs/skins/) for
the generation notes.

![Skin preview](docs/skins/preview.png)

*Gameplay screenshots are still needed — if you'd like to contribute some, open an issue or a PR.*

## License

[GNU GPL v3.0](LICENSE) — see the `LICENSE` file for the full text.
