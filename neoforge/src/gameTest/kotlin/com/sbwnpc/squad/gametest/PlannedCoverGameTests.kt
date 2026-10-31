package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.diagnostics.DecisionTrace
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.ai.planning.AgentLevel
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/**
 * A man under fire takes cover, peeks, holds or falls back as his own plan decides (#251), not
 * through a behaviour that suspends the plan: while he is in cover his plan stays movable, and the
 * decision trace names the method that sent him there.
 */
@PrefixGameTestTemplate(false)
object PlannedCoverGameTests {
    /**
     * One [role] in a squad of his own on DEFEND where he stands at the first of [men], and an enemy [shooter]
     * at [from] that hits him every [HIT_EVERY] ticks from tick [HIT_FROM]. With a [wall], a stone
     * wall two high at x 24 over those z lies between them a few blocks from him. Riflemen at
     * the rest of [men] join him.
     */
    private class UnderFire(val helper: GameTestHelper, name: String, men: List<Vec3>, from: Vec3, wall: IntRange?, role: NpcClass = NpcClass.RIFLEMAN) {
        val scene = CombatIdleGameTests.Scene(helper, name)
        val manager = SquadManager.get(helper.level)
        val man: NpcEntity = scene.spawn(men.first(), role)
        val shooter: NpcEntity = scene.enemy(from)
        val squad = manager.create(helper.level, UUID.randomUUID(), requireNotNull(man.spawnFaction), listOf(man.uuid) + men.drop(1).map { scene.spawn(it).uuid })

        /** Ticks on which he held cover while his plan was suspended: none is the contract. */
        var suspendedInCover = 0
            private set
        var firstHit: Long? = null
            private set

        /** Ticks, from his first move for cover, in the shooter's line with neither cover nor a peek claimed. */
        var exposedIdle = 0
            private set

        private val idleAt = ArrayList<String>()

        /** Exposed ticks between a new command and his new plan's first action. */
        var handovers = 0
            private set
        private var reacted = false

        /** Ticks he held cover, not peeking and not moving, still in the shooter's line. */
        var exposedInCover = 0
            private set

        /** Ticks he was out of the shooter's line. */
        var hidden = 0
            private set

        init {
            wall?.forEach { z -> for (y in 1..2) helper.setBlock(BlockPos(24, y, z), Blocks.STONE) }
            manager.setOrder(squad.id, SquadOrder.DEFEND)
            manager.setObjective(helper.level, squad.id, man.blockPosition())
            val groups = DebugFlags.enabledGroups
            DebugFlags.enabledGroups = groups + LogGroup.DIG
            scene.onClose = {
                DebugFlags.enabledGroups = groups
                manager.deleteSquad(helper.level, squad.id)
            }
        }

        /** Hits him on schedule until [until] and counts the ticks the contract is broken. */
        fun tick(until: Long = Long.MAX_VALUE) {
            val now = helper.tick
            val due = now >= HIT_FROM && (now - HIT_FROM) % HIT_EVERY == 0L
            if (due && now <= until && man.isAlive) {
                Ports.guns.inHand(shooter)!!.inflictHit(man, 1.0f)
                if (firstHit == null) firstHit = now
            }
            DecisionTrace.decisionsOf(man.uuid).filter { it.event == "plan" }.forEach { decision -> TAKEN.findAll(decision.detail).forEach { taken += it.groupValues[1] } }
            val claimed = man.holdsAny(NpcActivity.COVER, NpcActivity.PEEK)
            if (claimed && !NativeTactics.movable(man)) suspendedInCover++
            if (claimed) reacted = true
            if (man.holds(NpcActivity.COVER) && !man.holds(NpcActivity.PEEK) && man.navigation.isDone && exposed()) exposedInCover++
            when {
                !exposed() -> hidden++
                reacted && !claimed -> idle(now)
            }
        }

        /**
         * In the line with neither claim. A new command from his squad restarts his plan, with no
         * action for that tick: the hierarchy's handover, counted apart.
         */
        private fun idle(now: Long) {
            val actions = NativeTactics.reports(man).values
            if (actions.isEmpty()) {
                handovers++
                return
            }
            exposedIdle++
            if (idleAt.size < MAX_RECORDED) idleAt += "$now@${helper.relativeVec(man.position())} ${actions.map { "${it.ticket.identity}:${it.status}:${it.reason}" }}"
        }

        /** Out of the line except while moving for cover or peeking from it, and behind cover at times. */
        fun assertSheltered(who: String) {
            helper.assertTrue(reacted, "$who never took cover or peeked")
            helper.assertTrue(exposedIdle == 0, "$who stood in the shooter's line for $exposedIdle ticks neither taking cover nor peeking: $idleAt")
            helper.assertTrue(hidden > 0, "$who was never out of the shooter's line")
        }

        /** Whether the shooter's eye has a line to his. */
        fun exposed() = DetectionSightline.visible(helper.level, shooter.eyePosition, man.eyePosition, man)

        /** Every method his plans took, kept here: the decision trace holds only his latest decisions. */
        private val taken = HashSet<String>()

        /** Whether any of his plans took [method]. */
        fun chose(method: String) = method in taken

        /** Whether a plan of his after [tick] chose something other than his hole at its root. */
        fun outOfHoleSince(tick: Long) = DecisionTrace.decisionsOf(man.uuid).any { it.event == "plan" && it.tick > tick && it.detail.startsWith("root:") && "*in_hole" !in it.detail }

        fun choices() = DecisionTrace.decisionsOf(man.uuid).filter { it.event == "plan" }.map { it.detail }
    }

    /** Shot from 120 blocks off in the open, he gets behind the wall through his plan. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-cover-take")
    fun suppressedRiflemanTakesCoverInHisPlan(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-take", listOf(Vec3(20.5, 1.0, 64.5)), Vec3(140.5, 1.0, 64.5), wall = EXACT_WALL)
        helper.onEachTick {
            fire.tick()
            if (helper.tick % 50L == 0L) fire.scene.result("RUNNING", "tick=${helper.tick} at=${helper.relativeVec(fire.man.position())} exposed=${fire.exposed()} idle=${fire.exposedIdle} hidden=${fire.hidden} cover=${fire.man.holds(NpcActivity.COVER)} suspended=${fire.suspendedInCover} choices=${fire.choices()}")
            if (helper.tick >= 300L) {
                helper.assertTrue(fire.suspendedInCover == 0, "His plan was suspended for ${fire.suspendedInCover} ticks while he held cover")
                fire.assertSheltered("He")
                helper.assertTrue(fire.chose("under_fire") && fire.chose("take_cover"), "His plan did not choose cover: ${fire.choices()}")
                fire.scene.result("PASS", "tick=${helper.tick} hidden=${fire.hidden} handovers=${fire.handovers}")
                helper.succeed()
            }
        }
    }

    /**
     * The shooter about 3 degrees off due east, between two steps of the quantized hit bearing
     * (#264): cover judged from the rounded point alone left him at the wall's end, in the real
     * shooter's line.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-cover-off-step")
    fun coverHidesFromShooterOffTheBearingStep(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-off-step", listOf(Vec3(20.5, 1.0, 57.5)), Vec3(140.5, 1.0, 50.5), wall = WALL)
        helper.onEachTick {
            fire.tick()
            if (helper.tick % 50L == 0L) fire.scene.result("RUNNING", "tick=${helper.tick} at=${helper.relativeVec(fire.man.position())} exposed=${fire.exposed()} idle=${fire.exposedIdle} hidden=${fire.hidden}")
            if (helper.tick >= 300L) {
                fire.assertSheltered("He")
                helper.assertTrue(fire.exposedInCover == 0, "He settled in cover still in the shooter's line for ${fire.exposedInCover} ticks")
                fire.scene.result("PASS", "tick=${helper.tick} hidden=${fire.hidden}")
                helper.succeed()
            }
        }
    }

    /** Under fire from a shooter he sees, he peeks and fires from cover again and again, and holds fire while ducking. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 800, batch = "planned-cover-peek")
    fun pinnedRiflemanPeeksRepeatedlyUnderContinuousFire(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-peek", listOf(Vec3(20.5, 1.0, 57.5)), Vec3(60.5, 1.0, 50.5), wall = WALL)
        var peeks = 0
        var peeking = false
        var shotsWhileDucking = 0
        var lastShot = fire.man.lastShotTick
        helper.onEachTick {
            fire.tick()
            val nowPeeking = fire.man.holds(NpcActivity.PEEK)
            if (nowPeeking && !peeking) peeks++
            peeking = nowPeeking
            if (fire.man.lastShotTick != lastShot) {
                if (fire.man.holds(NpcActivity.COVER)) shotsWhileDucking++
                lastShot = fire.man.lastShotTick
            }
            if (helper.tick % 50L == 0L) fire.scene.result("RUNNING", "tick=${helper.tick} peeks=$peeks ducking-shots=$shotsWhileDucking suspended=${fire.suspendedInCover}")
            if (helper.tick >= 700L) {
                helper.assertTrue(fire.suspendedInCover == 0, "His plan was suspended for ${fire.suspendedInCover} ticks while he held cover")
                helper.assertTrue(peeks >= 3, "Only $peeks peeks in 700 ticks of fire")
                helper.assertTrue(fire.man.lastShotTick >= 0, "He never fired")
                helper.assertTrue(shotsWhileDucking == 0, "He fired $shotsWhileDucking times while ducking")
                helper.assertTrue(fire.chose("take_cover"), "His plan did not choose cover: ${fire.choices()}")
                fire.scene.result("PASS", "peeks=$peeks")
                helper.succeed()
            }
        }
    }

    /** No cover in reach and a shooter in sight: he holds and fires, through his plan. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-cover-hold")
    fun noCoverHoldsAndFires(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-hold", listOf(Vec3(20.5, 1.0, 100.5)), Vec3(56.5, 1.0, 100.5), wall = null)
        helper.onEachTick {
            fire.tick()
            if (helper.tick >= 300L) {
                helper.assertTrue(fire.suspendedInCover == 0, "His plan was suspended for ${fire.suspendedInCover} ticks while he held cover")
                helper.assertTrue(fire.man.lastShotTick >= 0, "He never fired")
                helper.assertTrue(fire.chose("hold_open"), "His plan did not hold in the open: ${fire.choices()}")
                fire.scene.result("PASS", "choices=${fire.choices()}")
                helper.succeed()
            }
        }
    }

    /** No cover in reach and nobody he can see: the shooter is 136 blocks off, past `NpcEntity.DETECTION_RANGE`. He falls back, through his plan. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-cover-fall-back")
    fun noCoverNoTargetFallsBack(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-fall-back", listOf(Vec3(140.5, 1.0, 100.5)), Vec3(4.5, 1.0, 100.5), wall = null)
        val start = fire.man.position().distanceTo(fire.shooter.position())
        helper.onEachTick {
            fire.tick()
            if (helper.tick >= 300L) {
                val gained = fire.man.position().distanceTo(fire.shooter.position()) - start
                helper.assertTrue(fire.suspendedInCover == 0, "His plan was suspended for ${fire.suspendedInCover} ticks while he held cover")
                helper.assertTrue(gained >= 4.0, "He fell back only ${"%.1f".format(gained)} blocks: ${fire.choices()}")
                helper.assertTrue(fire.chose("fall_back"), "His plan did not fall back: ${fire.choices()}")
                fire.scene.result("PASS", "gained=$gained choices=${fire.choices()}")
                helper.succeed()
            }
        }
    }

    /** A medic is planned like any ground man: under fire he takes cover in his plan. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-cover-medic")
    fun medicUnderFireTakesCoverInHisPlan(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-medic", listOf(Vec3(20.5, 1.0, 64.5)), Vec3(140.5, 1.0, 64.5), wall = EXACT_WALL, role = NpcClass.MEDIC)
        helper.onEachTick {
            fire.tick()
            if (helper.tick >= 300L) {
                helper.assertTrue(fire.suspendedInCover == 0, "His plan was suspended for ${fire.suspendedInCover} ticks while he held cover")
                fire.assertSheltered("The medic")
                helper.assertTrue(fire.chose("take_cover"), "His plan did not choose cover: ${fire.choices()}")
                fire.scene.result("PASS", "tick=${helper.tick} hidden=${fire.hidden} handovers=${fire.handovers}")
                helper.succeed()
            }
        }
    }

    /**
     * Badly hurt with no cover and a squadmate beside him, he digs in through his plan, holds the
     * hole, and once healed climbs back out of it.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 700, batch = "planned-cover-dig")
    fun hurtRiflemanDigsInAndClimbsOutOnceHealed(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-dig", listOf(Vec3(140.5, 1.0, 100.5), Vec3(143.5, 1.0, 103.5)), Vec3(4.5, 1.0, 100.5), wall = null)
        // After the scene, which lays its own stone floor.
        for (x in 134..146) for (z in 94..106) helper.setBlock(BlockPos(x, 0, z), Blocks.DIRT)
        fire.man.getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 100.0
        fire.man.health = 20f
        // A medkit would heal him above the dig threshold before he digs.
        fire.man.medkitsLeft = 0
        var dugAt: Long? = null
        var lowest = fire.man.y
        helper.onEachTick {
            val now = helper.tick
            val healAt = dugAt?.let { it + HOLD_BEFORE_HEAL }
            fire.tick(until = healAt ?: Long.MAX_VALUE)
            lowest = minOf(lowest, fire.man.y)
            if (dugAt == null && fire.man.holds(NpcActivity.DUG_IN)) dugAt = now
            if (now == healAt) fire.man.health = fire.man.maxHealth
            // Out on the surface, and his plan has stopped running from the hole: the climb is over.
            val out = healAt != null && now > healAt && fire.outOfHoleSince(healAt)
            if (out && helper.relativeVec(fire.man.position()).y >= 1.0) {
                helper.assertTrue(fire.suspendedInCover == 0, "His plan was suspended for ${fire.suspendedInCover} ticks while he held cover")
                helper.assertTrue(fire.chose("dig_in") && fire.chose("leave_hole"), "His plan did not dig in and leave: ${fire.choices()}")
                helper.assertTrue(helper.relativeVec(Vec3(0.0, lowest, 0.0)).y < 1.0, "He never stood in his hole")
                fire.scene.result("PASS", "dugAt=$dugAt outAt=$now choices=${fire.choices()}")
                helper.succeed()
            }
            if (now % 50L == 0L) fire.scene.result("RUNNING", "health=${fire.man.health}/${fire.man.maxHealth} tick=$now dugAt=$dugAt at=${helper.relativeVec(fire.man.position())} dug=${fire.man.holds(NpcActivity.DUG_IN)} choices=${fire.choices()}")
        }
    }

    /**
     * How soon he takes cover after the first hit: the plan must react about as soon as the
     * suspending behaviour did. [REACTION_TICKS] comes from this scene's runs on develop (30.10).
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 300, batch = "planned-cover-reaction")
    fun reactionTimeUnderFire(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-reaction", listOf(Vec3(20.5, 1.0, 57.5)), Vec3(140.5, 1.0, 50.5), wall = WALL)
        helper.onEachTick {
            fire.tick()
            val hit = fire.firstHit ?: return@onEachTick
            // His claim, not his displacement: a hit's knockback moves him too.
            if (fire.man.holdsAny(NpcActivity.COVER, NpcActivity.PEEK)) {
                val ticks = helper.tick - hit
                fire.scene.result("PASS", "reaction=$ticks")
                helper.assertTrue(ticks <= REACTION_TICKS, "He took $ticks ticks to take cover, more than $REACTION_TICKS")
                helper.succeed()
            }
        }
    }

    /** Continuous fire from one shooter must not make his plan restart every round. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 800, batch = "planned-cover-churn")
    fun coverPlanDoesNotChurn(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-churn", listOf(Vec3(20.5, 1.0, 57.5)), Vec3(60.5, 1.0, 50.5), wall = WALL)
        var plans = 0
        require(NativeTactics.planResultObserver == null)
        NativeTactics.planResultObserver = { level -> if (level == AgentLevel.NPC) plans++ }
        val close = fire.scene.onClose
        fire.scene.onClose = {
            NativeTactics.planResultObserver = null
            close()
        }
        helper.onEachTick {
            fire.tick()
            if (helper.tick >= 620L) {
                // One man, so every NPC-level result is his; 600 ticks of fire.
                fire.scene.result("PASS", "plans=$plans")
                helper.assertTrue(plans <= MAX_PLANS, "His plan was searched $plans times in 600 ticks of fire, more than $MAX_PLANS")
                helper.succeed()
            }
        }
    }

    /** A role the planner does not cover keeps the cover behaviour until #256. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 300, batch = "planned-cover-unplanned")
    fun unplannedRoleKeepsSeekCover(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-unplanned", listOf(Vec3(20.5, 1.0, 57.5)), Vec3(140.5, 1.0, 50.5), wall = WALL, role = NpcClass.MORTAR)
        var covered = false
        helper.onEachTick {
            fire.tick()
            covered = covered || fire.man.holds(NpcActivity.COVER)
            if (helper.tick >= 200L) {
                helper.assertTrue(covered, "The mortar crewman never took cover")
                fire.scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /**
     * Twice the 1 tick the suspending cover behaviour took to claim cover on develop 232d8fc3 (three
     * runs, 30.10). The plan took 2 in each of five runs.
     */
    private const val REACTION_TICKS = 2L

    /** A taken option in a plan decision's detail: `*name score`. */
    private val TAKEN = Regex("""\*(\w+)""")

    private const val HIT_EVERY = 20L

    /** Ticks in his hole under fire before the dig scene heals him. */
    private const val HOLD_BEFORE_HEAL = 100L
    private const val HIT_FROM = 20L

    /** Contract breaks a scene lists in its failure. */
    private const val MAX_RECORDED = 10

    /** The wall a few blocks from him, between him and a shooter to his east. */
    private val WALL = 46..54

    /**
     * The wall for scenes that check he is out of the shooter's line, with the shooter due east of
     * him: the bearing of a hit is quantized (`IncomingFire.record`), and only a bearing on a step,
     * as due east is, lets cover found from it hide him from the real shooter at its exact end cell.
     */
    private val EXACT_WALL = 52..62

    /**
     * Twice the most plans [coverPlanDoesNotChurn] searched in its first green runs (10 and 9, 30.10):
     * every finished cover cycle replans him by design (`COVER_CYCLES`), so it is not zero. Far more
     * means he replans on noise.
     */
    private const val MAX_PLANS = 20
}
