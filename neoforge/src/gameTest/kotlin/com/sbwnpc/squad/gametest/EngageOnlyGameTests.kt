package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.CombatPosition
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/**
 * A man who fights with no full plan of his own (no squad, or a role his squad does not plan
 * for) moves in the fight through an engage-only agent (#251 step B).
 */
@PrefixGameTestTemplate(false)
object EngageOnlyGameTests {
    /** A man without a squad still fights through his engage-only plan: far beyond his reach he is planned, closes in and fires. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "planned-engage-squadless")
    fun squadlessManFightsThroughHisEngage(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "planned-engage-squadless")
        val man = scene.spawn(Vec3(20.5, 1.0, 100.5))
        val enemy = scene.enemy(Vec3(110.5, 1.0, 100.5))
        man.target = enemy
        val start = man.position().distanceTo(enemy.position())
        helper.onEachTick {
            // No squad for the scene to pace: his own refresh builds his engage-only agent.
            if (helper.tick % 10L == 0L) SquadTactics.refresh(man)
            if (helper.tick >= 400L) {
                helper.assertTrue(SquadTactics.move(man), "No plan moves him")
                helper.assertTrue(man.lastShotTick >= 0, "He never fired")
                helper.assertTrue(man.position().distanceTo(enemy.position()) < start, "He never moved in")
                scene.result("PASS", "closed=${"%.1f".format(start - man.position().distanceTo(enemy.position()))}")
                helper.succeed()
            }
        }
    }

    /**
     * A mortar loader in a defending squad has no full plan; his engage-only one walks him
     * back into his area once he is pushed out of it, and keeps him there.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "planned-engage-return")
    fun mortarLoaderOutsideHisDefendAreaWalksBack(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "planned-engage-return")
        val manager = SquadManager.get(helper.level)
        val crewman = scene.spawn(Vec3(20.5, 1.0, 100.5), NpcClass.MORTAR_LOADER)
        val rifleman = scene.spawn(Vec3(20.5, 1.0, 104.5))
        val enemy = scene.enemy(Vec3(80.5, 1.0, 100.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), requireNotNull(crewman.spawnFaction), listOf(crewman.uuid, rifleman.uuid))
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, crewman.blockPosition())
        SquadTactics.observe(crewman, enemy)
        crewman.target = enemy
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        val home = crewman.position()
        val radius = CombatPosition.defendRadius(crewman.npcClass, squad.members.size)
        var pushed = false
        helper.onEachTick {
            scene.tick(squad)
            if (!pushed && helper.tick >= 60L) {
                crewman.teleportTo(home.x, home.y, home.z - radius - PUSH)
                pushed = true
            }
            if (helper.tick >= 500L) {
                helper.assertTrue(NativeTactics.hasAgent(crewman) && !NativeTactics.planned(crewman), "He fights without an engage-only agent")
                val off = crewman.position().distanceTo(home)
                helper.assertTrue(off <= radius + LEASH_SLACK, "He stayed outside his area: ${"%.1f".format(off)} > $radius")
                scene.result("PASS", "off=${"%.1f".format(off)}")
                helper.succeed()
            }
        }
    }

    /** A downed pilot on foot backs away from an enemy too near through his engage-only plan; a squadless man's plan is not traced, so the distance tells. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-engage-keep-away")
    fun downedPilotKeepsAwayFromANearEnemy(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "planned-engage-keep-away")
        val pilot = scene.spawn(Vec3(60.5, 1.0, 100.5), NpcClass.HELICOPTER_PILOT)
        val enemy = scene.enemy(Vec3(80.5, 1.0, 100.5))
        pilot.target = enemy
        val start = pilot.position().distanceTo(enemy.position())
        helper.onEachTick {
            if (helper.tick % 10L == 0L) SquadTactics.refresh(pilot)
            if (helper.tick >= 300L) {
                val now = pilot.position().distanceTo(enemy.position())
                helper.assertTrue(NativeTactics.hasAgent(pilot), "He has no engage-only agent")
                helper.assertTrue(now > start + KEPT_AWAY, "He backed off only ${"%.1f".format(now - start)} blocks")
                scene.result("PASS", "away=${"%.1f".format(now - start)}")
                helper.succeed()
            }
        }
    }

    /** A squadless man who loses sight of his enemy for a moment keeps his engage-only agent. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-engage-blink")
    fun engagerKeepsHisAgentThroughAMomentOutOfSight(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "planned-engage-blink")
        val man = scene.spawn(Vec3(20.5, 1.0, 100.5))
        val enemy = scene.enemy(Vec3(50.5, 1.0, 100.5))
        man.target = enemy
        helper.onEachTick {
            if (helper.tick % 10L == 0L) SquadTactics.refresh(man)
            // A wall up for a second hides the enemy: his gun stops reporting the fight that long.
            val wall = if (helper.tick in WALL_UP until WALL_UP + WALL_TICKS) Blocks.STONE else Blocks.AIR
            if (helper.tick == WALL_UP || helper.tick == WALL_UP + WALL_TICKS) {
                for (y in 1..3) for (z in 96..105) helper.setBlock(BlockPos(35, y, z), wall)
            }
            if (helper.tick >= WALL_UP) helper.assertTrue(NativeTactics.hasAgent(man), "He lost his engage-only agent at tick ${helper.tick}")
            if (helper.tick >= WALL_UP + WALL_TICKS + 60L) {
                helper.assertTrue(man.lastShotTick >= 0, "He never fired")
                scene.result("PASS", "kept")
                helper.succeed()
            }
        }
    }

    /**
     * A squadless man suppressed with a wall nearby: SeekCover, an immediate reaction outside his
     * plan, takes his feet. While it holds them no engage unit of his runs, so the two never steer
     * him at once, and he reaches the wall.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-engage-suppressed")
    fun suppressedEngagerTakesCoverWithoutHisEngage(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "planned-engage-suppressed")
        val man = scene.spawn(Vec3(20.5, 1.0, 100.5))
        val enemy = scene.enemy(Vec3(60.5, 1.0, 100.5))
        for (y in 1..2) for (z in 103..107) helper.setBlock(BlockPos(24, y, z), Blocks.STONE)
        man.target = enemy
        var covered = 0
        helper.onEachTick {
            if (helper.tick % 10L == 0L) SquadTactics.refresh(man)
            if (helper.tick in SUPPRESS_FROM..SUPPRESS_UNTIL) man.suppress(enemy.eyePosition)
            if (man.holds(NpcActivity.COVER)) {
                covered++
                helper.assertTrue(!SquadTactics.move(man), "His engage steered him while SeekCover held his feet at tick ${helper.tick}")
            }
            if (helper.tick >= SUPPRESS_UNTIL) {
                helper.assertTrue(covered > 0, "He never took cover under fire")
                scene.result("PASS", "covered=$covered")
                helper.succeed()
            }
        }
    }

    /** A bound may end a block or two past the edge of his area before the next one turns him back. */
    private const val LEASH_SLACK = 3.0

    /** Blocks past the edge of his area he is put. */
    private const val PUSH = 10.0

    /** Blocks a downed pilot has to back off to count as keeping away: more than a single step's drift. */
    private const val KEPT_AWAY = 4.0

    /** The tick the wall goes up, once he is fighting, and how long it stays: under ENGAGER_TICKS (40) of not reporting. */
    private const val WALL_UP = 120L
    private const val WALL_TICKS = 20L

    /** He is suppressed from once he fights until the scene ends. */
    private const val SUPPRESS_FROM = 60L
    private const val SUPPRESS_UNTIL = 300L
}
