package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.data.gun.GunData
import com.sbwnpc.squad.ai.diagnostics.DecisionTrace
import com.sbwnpc.squad.combat.CombatPosition
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Entity
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

    /**
     * An LAV parked across his line of fire: his plan looks for a spot clear of it, and looks
     * again at most once per rest while the hull stays, not every tick he walks.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-engage-hull")
    fun hullInTheLaneMakesHimSearchOnce(helper: GameTestHelper) {
        val contact = Contact(helper, "planned-engage-hull", 15.0)
        var parkedAt: Long? = null
        var parkedGameTime = 0L
        var lav: Entity? = null
        contact.scene.onClose.let { close ->
            contact.scene.onClose = {
                lav?.discard()
                close()
            }
        }
        helper.onEachTick {
            contact.tick()
            if (parkedAt == null && contact.man.lastShotTick >= 0) {
                val at = contact.man.position().add(contact.enemy.position()).scale(0.5)
                lav = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25)).also {
                    it.moveTo(at.x, at.y, at.z, 0f, 0f)
                    helper.level.addFreshEntity(it)
                }
                parkedAt = helper.tick
                parkedGameTime = helper.level.gameTime
            }
            val since = parkedAt ?: return@onEachTick
            if (helper.tick - since >= HULL_TICKS) {
                val searches = DecisionTrace.decisionsOf(contact.man.uuid).count { it.event == "plan" && it.tick >= parkedGameTime && "*research_spot" in it.detail }
                helper.assertTrue(searches in 1..MAX_SEARCHES, "He searched $searches times in $HULL_TICKS ticks behind a hull")
                contact.scene.result("PASS", "searches=$searches")
                helper.succeed()
            }
        }
    }

    /** A taken option in a plan decision's detail: `*name score`. */
    private val TAKEN = Regex("""\*(\w+)""")

    /** How long the hull stands in his lane, and the searches allowed in it: one per 60-tick rest, and the first. */
    private const val HULL_TICKS = 100L
    private const val MAX_SEARCHES = 3

    /** Blocks forward that count as having closed in: two bounds' worth. */
    private const val CLOSED = 6.0

    /** A bound may end a block or two past the edge of his area before the next one turns him back. */
    private const val LEASH_SLACK = 3.0

    /** Blocks back along x the new order sends the squad. */
    private const val REORDER = 15

    /** Blocks off along x the enemy falls back to: far past any role's engage distance. */
    private const val RETREAT = 130.0

    /** Blocks off his spot that count as having marched. */
    private const val MARCHED = 5.0
}
