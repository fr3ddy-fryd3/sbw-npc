package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.FiringCone
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.domain.port.Guns
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.gametest.CombatIdleGameTests.Scene
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/** Real-world measurements; timings are evidence, deterministic work counts are assertions. */
@PrefixGameTestTemplate(false)
object CombatPerformanceGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 400, batch = "failed-combat-path")
    fun failedBlindApproachBacksOffAndResumesAfterTerrainOpens(helper: GameTestHelper) {
        val scene = Scene(helper, "failed-combat-path")
        for (x in 40..41) for (z in 0..223) for (y in 1..8) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        val shooter = scene.spawn(Vec3(30.5, 1.0, 100.5))
        scene.enemy(Vec3(150.5, 1.0, 100.5))
        val squad = scene.squad(listOf(shooter))
        val nav = shooter.navigation as VehicleAwareNavigation
        var blockedSearches = 0
        var blockedNanos = 0L
        nav.positionPathObserver = { _, nanos, _ ->
            if (helper.tick < 80) {
                blockedSearches++
                blockedNanos += nanos
            }
        }
        scene.onClose = { nav.positionPathObserver = null }
        helper.runAtTickTime(80) {
            scene.result("MEASURED", "blockedSearches=$blockedSearches blockedSearchMillis=${blockedNanos / 1_000_000.0}")
            helper.assertTrue(blockedSearches in 1..12, "Unchanged failed approach rebuilt $blockedSearches paths in 80 ticks")
            for (x in 40..41) for (z in 0..223) for (y in 1..8) helper.setBlock(BlockPos(x, y, z), Blocks.AIR)
        }
        helper.onEachTick {
            scene.tick(squad)
            if (helper.tick > 80 && helper.relativeVec(shooter.position()).x > 50.0 && shooter.lastShotTick >= 0) {
                scene.result("PASS", "blockedSearches=$blockedSearches blockedSearchMillis=${blockedNanos / 1_000_000.0} resumed=${helper.tick} at=${helper.relativeVec(shooter.position())}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 100, batch = "friendly-fire-work")
    fun friendlyFireStopsCompletedAssessmentAndKeepsBlastAndMotionSafety(helper: GameTestHelper) {
        val scene = Scene(helper, "friendly-fire-work")
        val shooter = scene.spawn(Vec3(10.5, 1.0, 100.5)).apply { isNoAi = true }
        val blocker = scene.spawn(Vec3(20.5, 1.0, 100.5)).apply { isNoAi = true }
        val impactAlly = scene.spawn(Vec3(150.5, 1.0, 120.5)).apply { isNoAi = true }
        repeat(204) { index -> scene.spawn(Vec3(20.5 + index % 20 * 6, 1.0, 20.5 + index / 20 * 6)).isNoAi = true }
        val impact = helper.absoluteVec(Vec3(150.5, 1.0, 100.5))
        val aim = impact.add(0.0, shooter.eyeHeight.toDouble(), 0.0)
        val actualGuns = Ports.guns
        var bounds = 0
        val counting = object : Guns by actualGuns {
            override fun firingSafetyBounds(entity: LivingEntity, flightTicks: Double): AABB {
                bounds++
                return actualGuns.firingSafetyBounds(entity, flightTicks)
            }
        }
        fun place(npc: NpcEntity, at: Vec3) {
            npc.setPos(helper.absoluteVec(at))
            npc.xOld = npc.x
            npc.yOld = npc.y
            npc.zOld = npc.z
            npc.deltaMovement = Vec3.ZERO
        }
        helper.runAtTickTime(10) {
            Ports.guns = counting
            try {
                place(blocker, Vec3(20.5, 1.0, 100.5))
                place(impactAlly, Vec3(150.5, 1.0, 120.5))
                val evidence = mutableListOf<String>()
                for (variant in 0..3) {
                    val blast = if (variant == 1 || variant == 2) 6.0 else 0.0
                    if (variant == 1) place(impactAlly, Vec3(150.5, 1.0, 104.5))
                    if (variant == 2) place(blocker, Vec3(20.5, 1.0, 120.5))
                    if (variant == 3) {
                        place(blocker, Vec3(20.5, 1.0, 110.5))
                        blocker.pose = Pose.CROUCHING
                        blocker.deltaMovement = Vec3(0.0, 0.0, -2.0)
                    }
                    val expected = referenceAssessment(shooter, aim, 0.0, impact, blast)
                    val actual = FriendlyFireGuard.assess(shooter, aim, 0.0, impact, blast)
                    helper.assertTrue(actual.lineClear == expected.lineClear && actual.blastClear == expected.blastClear, "Optimization changed projectile/blast safety in variant $variant")
                    evidence += "variant=$variant line=${actual.lineClear} blast=${actual.blastClear}"
                }
                place(blocker, Vec3(20.5, 1.0, 100.5))
                blocker.pose = Pose.STANDING
                place(impactAlly, Vec3(150.5, 1.0, 120.5))
                bounds = 0
                var started = System.nanoTime()
                repeat(128) { referenceAssessment(shooter, aim, 0.0, impact, 0.0) }
                val referenceNanos = System.nanoTime() - started
                val referenceBounds = bounds
                bounds = 0
                started = System.nanoTime()
                repeat(128) { FriendlyFireGuard.assess(shooter, aim, 0.0, impact, 0.0) }
                val currentNanos = System.nanoTime() - started
                val currentBounds = bounds
                scene.result("MEASURED", "referenceBounds=$referenceBounds currentBounds=$currentBounds referenceMillis=${referenceNanos / 1_000_000.0} currentMillis=${currentNanos / 1_000_000.0} safety=$evidence")
                helper.assertTrue(referenceBounds >= 200 * 128 && currentBounds <= 4 * 128, "Completed line check still evaluates all 207 NPCs: reference=$referenceBounds current=$currentBounds")
                scene.result("PASS", "referenceBounds=$referenceBounds currentBounds=$currentBounds referenceMillis=${referenceNanos / 1_000_000.0} currentMillis=${currentNanos / 1_000_000.0} safety=$evidence")
                helper.succeed()
            } finally {
                Ports.guns = actualGuns
            }
        }
    }

    /** Frozen pre-optimization implementation, an oracle and work-count baseline for the benchmark. */
    private fun referenceAssessment(shooter: NpcEntity, aim: Vec3, spread: Double, impact: Vec3, blast: Double): FriendlyFireGuard.Assessment {
        val level = shooter.level() as ServerLevel
        val cone = FiringCone(shooter.eyePosition, aim, spread)
        val speed = Ports.guns.inHand(shooter)?.muzzleVelocity?.coerceAtLeast(1.0) ?: 20.0
        val flight = kotlin.math.ceil(shooter.eyePosition.distanceTo(aim) / speed) + 1.0
        val checkBlast = blast > 0.0
        val blastBox = AABB(impact, impact).inflate(blast)
        var lineClear = true
        var blastClear = true
        forEachReferenceAlly(shooter, { cone.bounds.intersects(Ports.guns.firingSafetyBounds(it, flight)) || (checkBlast && blastBox.contains(it.position())) }) { ally ->
            if (lineClear && Ports.guns.canHitEntity(shooter, ally) && cone.intersects(Ports.guns.firingSafetyBounds(ally, flight))) lineClear = false
            if (blastClear && checkBlast && (ally !is Player || (!ally.isCreative && !ally.isSpectator)) && ally.position().distanceTo(impact) <= blast) blastClear = false
            if (!lineClear && !blastClear) return FriendlyFireGuard.Assessment(false, false)
        }
        return FriendlyFireGuard.Assessment(lineClear, blastClear)
    }

    private inline fun forEachReferenceAlly(shooter: NpcEntity, near: (LivingEntity) -> Boolean, action: (LivingEntity) -> Unit) {
        val level = shooter.level() as ServerLevel
        for (npc in NpcRegistry.all(level)) {
            if (npc !== shooter && near(npc) && !SquadTeams.isHostile(shooter, npc)) action(npc)
        }
        for (player in level.players()) {
            if (near(player) && !SquadTeams.isHostile(shooter, player)) action(player)
        }
    }
}
