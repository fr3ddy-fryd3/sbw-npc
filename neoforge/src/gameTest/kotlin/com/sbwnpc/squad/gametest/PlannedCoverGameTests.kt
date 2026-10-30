package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.diagnostics.DecisionTrace
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.ai.planning.AgentLevel
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
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
 * A man under fire takes cover, peeks, holds or falls back as his own plan decides (#251), not
 * through a behaviour that suspends the plan: while he is in cover his plan stays movable, and the
 * decision trace names the method that sent him there.
 */
@PrefixGameTestTemplate(false)
object PlannedCoverGameTests {
    /**
     * One [role] in a squad of his own on DEFEND where he stands at [at], and an enemy [shooter]
     * at [from] that hits him every [every] ticks from tick [startAt]. With [wall], a stone wall
     * two high at x 24, z 46..54 lies between them a few blocks from him.
     */
    private class UnderFire(val helper: GameTestHelper, name: String, at: Vec3, from: Vec3, wall: Boolean, role: NpcClass = NpcClass.RIFLEMAN, val every: Long = 20, val startAt: Long = 20) {
        val scene = CombatIdleGameTests.Scene(helper, name)
        val manager = SquadManager.get(helper.level)
        val man: NpcEntity = scene.spawn(at, role)
        val shooter: NpcEntity = scene.enemy(from)
        val squad = manager.create(helper.level, UUID.randomUUID(), requireNotNull(man.spawnFaction), listOf(man.uuid))

        /** Ticks on which he held cover while his plan was suspended: none is the contract. */
        var suspendedInCover = 0
            private set
        var firstHit: Long? = null
            private set

        init {
            if (wall) for (z in 46..54) for (y in 1..2) helper.setBlock(BlockPos(24, y, z), Blocks.STONE)
            manager.setOrder(squad.id, SquadOrder.DEFEND)
            manager.setObjective(helper.level, squad.id, man.blockPosition())
            scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        }

        /** Hits him on schedule until [until] and counts the ticks the contract is broken. */
        fun tick(until: Long = Long.MAX_VALUE) {
            val now = helper.tick
            if (now >= startAt && now <= until && (now - startAt) % every == 0L && man.isAlive) {
                Ports.guns.inHand(shooter)!!.inflictHit(man, 1.0f)
                if (firstHit == null) firstHit = now
            }
            if (man.holdsAny(NpcActivity.COVER, NpcActivity.PEEK) && !NativeTactics.movable(man)) suspendedInCover++
        }

        /** Whether the shooter's eye has a line to his. */
        fun exposed() = DetectionSightline.visible(helper.level, shooter.eyePosition, man.eyePosition, man)

        /** Whether his recorded plan choices include [method] as the one taken. */
        fun chose(method: String) = DecisionTrace.decisionsOf(man.uuid).any { it.event == "plan" && "*$method" in it.detail }

        fun choices() = DecisionTrace.decisionsOf(man.uuid).filter { it.event == "plan" }.map { it.detail }
    }

    /** Shot from afar by a shooter he cannot see, he gets behind the wall through his plan. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-cover-take")
    fun suppressedRiflemanTakesCoverInHisPlan(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-take", Vec3(20.5, 1.0, 57.5), Vec3(140.5, 1.0, 50.5), wall = true)
        helper.onEachTick {
            fire.tick()
            if (helper.tick % 50L == 0L) fire.scene.result("RUNNING", "tick=${helper.tick} at=${helper.relativeVec(fire.man.position())} exposed=${fire.exposed()} cover=${fire.man.holds(NpcActivity.COVER)} suspended=${fire.suspendedInCover} choices=${fire.choices()}")
            if (helper.tick >= 300L) {
                helper.assertTrue(fire.suspendedInCover == 0, "His plan was suspended for ${fire.suspendedInCover} ticks while he held cover")
                helper.assertTrue(!fire.exposed(), "He is still in the shooter's line at ${helper.relativeVec(fire.man.position())}")
                helper.assertTrue(fire.chose("under_fire") && fire.chose("take_cover"), "His plan did not choose cover: ${fire.choices()}")
                fire.scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** Under fire from a shooter he sees, he peeks and fires from cover again and again, and holds fire while ducking. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 800, batch = "planned-cover-peek")
    fun pinnedRiflemanPeeksRepeatedlyUnderContinuousFire(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-peek", Vec3(20.5, 1.0, 57.5), Vec3(60.5, 1.0, 50.5), wall = true)
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
        val fire = UnderFire(helper, "planned-cover-hold", Vec3(20.5, 1.0, 100.5), Vec3(56.5, 1.0, 100.5), wall = false)
        helper.onEachTick {
            fire.tick()
            if (helper.tick >= 300L) {
                helper.assertTrue(fire.suspendedInCover == 0, "His plan was suspended for ${fire.suspendedInCover} ticks while he held cover")
                helper.assertTrue(fire.man.lastShotTick >= 0, "He never fired")
                helper.assertTrue(fire.chose("under_fire"), "His plan never took up the fire: ${fire.choices()}")
                fire.scene.result("PASS", "choices=${fire.choices()}")
                helper.succeed()
            }
        }
    }

    /** No cover in reach and nobody to fire at: he falls back from the fire, through his plan. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-cover-fall-back")
    fun noCoverNoTargetFallsBack(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-fall-back", Vec3(20.5, 1.0, 100.5), Vec3(140.5, 1.0, 100.5), wall = false)
        val start = fire.man.position().distanceTo(fire.shooter.position())
        helper.onEachTick {
            fire.tick()
            if (helper.tick >= 300L) {
                val gained = fire.man.position().distanceTo(fire.shooter.position()) - start
                helper.assertTrue(fire.suspendedInCover == 0, "His plan was suspended for ${fire.suspendedInCover} ticks while he held cover")
                helper.assertTrue(gained >= 4.0, "He fell back only ${"%.1f".format(gained)} blocks")
                helper.assertTrue(fire.chose("under_fire"), "His plan never took up the fire: ${fire.choices()}")
                fire.scene.result("PASS", "gained=$gained choices=${fire.choices()}")
                helper.succeed()
            }
        }
    }

    /** A medic is planned like any ground man: under fire he takes cover in his plan. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "planned-cover-medic")
    fun medicUnderFireTakesCoverInHisPlan(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-medic", Vec3(20.5, 1.0, 57.5), Vec3(140.5, 1.0, 50.5), wall = true, role = NpcClass.MEDIC)
        helper.onEachTick {
            fire.tick()
            if (helper.tick >= 300L) {
                helper.assertTrue(fire.suspendedInCover == 0, "His plan was suspended for ${fire.suspendedInCover} ticks while he held cover")
                helper.assertTrue(!fire.exposed(), "The medic is still in the shooter's line")
                helper.assertTrue(fire.chose("take_cover"), "His plan did not choose cover: ${fire.choices()}")
                fire.scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /**
     * How soon he moves for cover after the first hit: the plan must react about as soon as the
     * suspending behaviour did. [REACTION_TICKS] comes from this scene's run on develop (30.10).
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 300, batch = "planned-cover-reaction")
    fun reactionTimeUnderFire(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-reaction", Vec3(20.5, 1.0, 57.5), Vec3(140.5, 1.0, 50.5), wall = true)
        val start = fire.man.position()
        helper.onEachTick {
            fire.tick()
            val hit = fire.firstHit ?: return@onEachTick
            if (fire.man.position().distanceTo(start) >= 0.5) {
                val ticks = helper.tick - hit
                fire.scene.result("PASS", "reaction=$ticks")
                helper.assertTrue(ticks <= REACTION_TICKS, "He took $ticks ticks to move for cover, more than $REACTION_TICKS")
                helper.succeed()
            }
        }
    }

    /** Continuous fire from one shooter must not make his plan restart every round. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 800, batch = "planned-cover-churn")
    fun coverPlanDoesNotChurn(helper: GameTestHelper) {
        val fire = UnderFire(helper, "planned-cover-churn", Vec3(20.5, 1.0, 57.5), Vec3(60.5, 1.0, 50.5), wall = true)
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
        val fire = UnderFire(helper, "planned-cover-unplanned", Vec3(20.5, 1.0, 57.5), Vec3(140.5, 1.0, 50.5), wall = true, role = NpcClass.MORTAR)
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

    /** Twice the 5 ticks the suspending cover behaviour took on develop 232d8fc3 (30.10). */
    private const val REACTION_TICKS = 10L

    /** Set from the first green run of [coverPlanDoesNotChurn]. */
    private const val MAX_PLANS = 1000
}
