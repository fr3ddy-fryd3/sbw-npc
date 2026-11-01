package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.data.gun.GunData
import com.sbwnpc.squad.ai.diagnostics.DecisionTrace
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.CombatPosition
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.entity.NpcEntity
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
 * A man in contact whose duty leaves his movement to his order (FOLLOW_ORDER, here a MOVE order
 * he has arrived on) moves in the fight as his own plan's `npc.engage` method says (#251 step B),
 * not as his gun behaviour decides by itself.
 */
@PrefixGameTestTemplate(false)
object PlannedEngageGameTests {
    /**
     * Two riflemen on a MOVE order to where they stand, at x 20, and a passive enemy at x 20 +
     * [distance] that the squad has reported. With [sidearm], the first man's primary is empty and
     * he fights with his pistol.
     */
    private class Contact(val helper: GameTestHelper, name: String, distance: Double, sidearm: Boolean = false) {
        val scene = CombatIdleGameTests.Scene(helper, name)
        val manager = SquadManager.get(helper.level)
        val man: NpcEntity = scene.spawn(Vec3(20.5, 1.0, 100.5))
        val mate = scene.spawn(Vec3(20.5, 1.0, 104.5))
        val enemy: NpcEntity = scene.enemy(Vec3(20.5 + distance, 1.0, 100.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), requireNotNull(man.spawnFaction), listOf(man.uuid, mate.uuid))
        val start: Vec3 = man.position()
        private val taken = HashSet<String>()

        init {
            if (sidearm) {
                GunData.from(man.primaryGun()).apply {
                    ammo.set(0)
                    virtualAmmo.set(0)
                    save()
                }
            }
            manager.setOrder(squad.id, SquadOrder.MOVE)
            manager.setObjective(helper.level, squad.id, man.blockPosition())
            SquadTactics.observe(man, enemy)
            SquadTactics.refresh(man)
            scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        }

        fun tick() {
            scene.tick(squad)
            DecisionTrace.decisionsOf(man.uuid).filter { it.event == "plan" }.forEach { decision -> TAKEN.findAll(decision.detail).forEach { taken += it.groupValues[1] } }
        }

        fun chose(method: String) = method in taken

        fun choices() = taken.sorted()

        /** How much nearer the enemy he stands than where he started. */
        fun closed() = start.distanceTo(enemy.position()) - man.position().distanceTo(enemy.position())
    }

    /** The enemy within his stopping distance: his plan holds a firing spot and he fires from it. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-engage-hold")
    fun arrivedManHoldsAFiringSpotInHisPlan(helper: GameTestHelper) {
        val contact = Contact(helper, "planned-engage-hold", 15.0)
        helper.onEachTick {
            contact.tick()
            if (helper.tick >= 300L) {
                helper.assertTrue(contact.chose("hold_firing_spot"), "His plan did not hold a firing spot: ${contact.choices()}")
                helper.assertTrue(contact.man.lastShotTick >= 0, "He never fired")
                contact.scene.result("PASS", "choices=${contact.choices()}")
                helper.succeed()
            }
        }
    }

    /** The enemy far beyond his stopping distance in the open: his plan closes in by bounds. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "planned-engage-close")
    fun arrivedManClosesInByBoundsInHisPlan(helper: GameTestHelper) {
        val contact = Contact(helper, "planned-engage-close", 90.0)
        helper.onEachTick {
            contact.tick()
            if (helper.tick >= 400L) {
                helper.assertTrue(contact.chose("close_in"), "His plan did not close in: ${contact.choices()}")
                helper.assertTrue(contact.closed() > CLOSED, "He closed only ${"%.1f".format(contact.closed())} blocks")
                contact.scene.result("PASS", "closed=${"%.1f".format(contact.closed())} choices=${contact.choices()}")
                helper.succeed()
            }
        }
    }

    /** On his pistol his plan never sends him forward (owner, 31.10): he holds a spot near where he is. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "planned-engage-sidearm")
    fun sidearmManHoldsInsteadOfClosingIn(helper: GameTestHelper) {
        val contact = Contact(helper, "planned-engage-sidearm", 90.0, sidearm = true)
        helper.onEachTick {
            contact.tick()
            if (helper.tick > 20L) helper.assertTrue(contact.man.usingSidearm, "He is not on his pistol")
            if (helper.tick >= 400L) {
                helper.assertTrue(contact.chose("hold_firing_spot"), "His plan did not hold a firing spot: ${contact.choices()}")
                helper.assertTrue(!contact.chose("close_in"), "His plan closed in on a pistol: ${contact.choices()}")
                helper.assertTrue(contact.closed() < CLOSED, "He went ${"%.1f".format(contact.closed())} blocks forward on a pistol")
                contact.scene.result("PASS", "closed=${"%.1f".format(contact.closed())} choices=${contact.choices()}")
                helper.succeed()
            }
        }
    }

    /**
     * A DEFEND order mid-engage replans him: the engage he had under MOVE gives way to his defence,
     * and he stays inside his defend area round the objective instead of closing on the enemy.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "planned-engage-replaced")
    fun defendOrderKeepsHimInsideHisArea(helper: GameTestHelper) {
        val contact = Contact(helper, "planned-engage-replaced", 90.0)
        var home: Vec3? = null
        helper.onEachTick {
            contact.tick()
            if (helper.tick == 200L) {
                helper.assertTrue(contact.chose("engage"), "His plan never engaged: ${contact.choices()}")
                home = contact.man.position()
                contact.manager.setOrder(contact.squad.id, SquadOrder.DEFEND)
                contact.manager.setObjective(helper.level, contact.squad.id, contact.man.blockPosition())
            }
            val at = home ?: return@onEachTick
            val radius = CombatPosition.defendRadius(contact.man.npcClass, contact.squad.members.size)
            helper.assertTrue(contact.man.position().distanceTo(at) <= radius + LEASH_SLACK, "He left his defend area: ${"%.1f".format(contact.man.position().distanceTo(at))} > $radius")
            if (helper.tick >= 400L) {
                contact.scene.result("PASS", "choices=${contact.choices()}")
                helper.succeed()
            }
        }
    }

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

    /** A man who dies while his engage holds a firing spot leaves it free for the others. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-engage-death")
    fun deathReleasesHisFiringSpot(helper: GameTestHelper) {
        val contact = Contact(helper, "planned-engage-death", 15.0)
        var spot: Vec3? = null
        helper.onEachTick {
            contact.tick()
            if (spot == null && contact.chose("hold_firing_spot") && FiringSpots.nearby(contact.man.position(), 0.0, contact.mate.uuid).isNotEmpty()) {
                spot = contact.man.position()
                contact.man.kill()
            }
            val at = spot ?: return@onEachTick
            if (helper.tick >= 300L) {
                helper.assertTrue(FiringSpots.nearby(at, 0.0, contact.mate.uuid).isEmpty(), "His firing spot is still claimed after his death")
                contact.scene.result("PASS", "released")
                helper.succeed()
            }
        }
    }

    /** A fresh far target gets his opening burst before his plan moves him towards it. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-engage-opening")
    fun openingBurstComesBeforeHeMoves(helper: GameTestHelper) {
        val contact = Contact(helper, "planned-engage-opening", 90.0)
        var movedAt: Long? = null
        var firedAt: Long? = null
        helper.onEachTick {
            contact.tick()
            if (firedAt == null && contact.man.lastShotTick >= 0) firedAt = helper.tick
            if (movedAt == null && contact.closed() > 1.0) movedAt = helper.tick
            if (helper.tick >= 300L) {
                helper.assertTrue(contact.chose("open_fire"), "His plan made no opening fire: ${contact.choices()}")
                val fired = firedAt
                helper.assertTrue(fired != null && fired <= (movedAt ?: Long.MAX_VALUE), "He moved at $movedAt before his first shot at $firedAt")
                contact.scene.result("PASS", "fired=$firedAt moved=$movedAt choices=${contact.choices()}")
                helper.succeed()
            }
        }
    }

    /** His mate standing in his line of fire makes his plan step him aside, and he fires again. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "planned-engage-sidestep")
    fun allyInTheLaneMakesHimStepAside(helper: GameTestHelper) {
        val contact = Contact(helper, "planned-engage-sidestep", 15.0)
        var blockedAt: Long? = null
        helper.onEachTick {
            contact.tick()
            if (blockedAt == null && contact.chose("hold_firing_spot") && contact.man.lastShotTick >= 0) {
                // Halfway down his line of fire, still, so only his own move can clear it.
                val lane = contact.man.position().add(contact.enemy.position()).scale(0.5)
                contact.mate.isNoAi = true
                contact.mate.teleportTo(lane.x, lane.y, lane.z)
                blockedAt = helper.tick
            }
            val since = blockedAt ?: return@onEachTick
            if (helper.tick - since >= 200L) {
                helper.assertTrue(contact.chose("sidestep"), "His plan never stepped him aside: ${contact.choices()}")
                helper.assertTrue(contact.man.lastShotTick > since, "He never fired again after the lane was blocked")
                contact.scene.result("PASS", "choices=${contact.choices()}")
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

    /**
     * The fight ends in a march: his enemy falls back out of reach and the squad gets a MOVE
     * order. His engage gives way to the march, and the firing spot it held is free again.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "planned-engage-reordered")
    fun marchingOrderReleasesHisFiringSpot(helper: GameTestHelper) {
        val contact = Contact(helper, "planned-engage-reordered", 15.0)
        var spot: Vec3? = null
        var orderedAt = 0L
        helper.onEachTick {
            contact.tick()
            if (spot == null && contact.chose("hold_firing_spot") && FiringSpots.nearby(contact.man.position(), 0.0, contact.mate.uuid).isNotEmpty()) {
                spot = contact.man.position()
                orderedAt = helper.tick
                val away = helper.absoluteVec(Vec3(20.5 + RETREAT, 1.0, 100.5))
                contact.enemy.teleportTo(away.x, away.y, away.z)
                contact.manager.setOrder(contact.squad.id, SquadOrder.MOVE)
                contact.manager.setObjective(helper.level, contact.squad.id, contact.man.blockPosition().offset(-REORDER, 0, 0))
            }
            val at = spot ?: return@onEachTick
            if (helper.tick - orderedAt >= 200L) {
                helper.assertTrue(FiringSpots.nearby(at, 0.0, contact.mate.uuid).isEmpty(), "His firing spot is still claimed on the march")
                helper.assertTrue(contact.man.position().distanceTo(at) > MARCHED, "He never marched off his spot")
                contact.scene.result("PASS", "released")
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

    /** A taken option in a plan decision's detail: `*name score`. */
    private val TAKEN = Regex("""\*(\w+)""")

    /** Blocks forward that count as having closed in: two bounds' worth. */
    private const val CLOSED = 6.0

    /** A bound may end a block or two past the edge of his area before the next one turns him back. */
    private const val LEASH_SLACK = 3.0

    /** Blocks past the edge of his area he is put. */
    private const val PUSH = 10.0

    /** Blocks a downed pilot has to back off to count as keeping away: more than a single step's drift. */
    private const val KEPT_AWAY = 4.0

    /** Blocks back along x the new order sends the squad. */
    private const val REORDER = 15

    /** Blocks off along x the enemy falls back to: far past any role's engage distance. */
    private const val RETREAT = 130.0

    /** Blocks off his spot that count as having marched. */
    private const val MARCHED = 5.0

    /** The tick the wall goes up, once he is fighting, and how long it stays: under ENGAGER_TICKS (40) of not reporting. */
    private const val WALL_UP = 120L
    private const val WALL_TICKS = 20L
}
