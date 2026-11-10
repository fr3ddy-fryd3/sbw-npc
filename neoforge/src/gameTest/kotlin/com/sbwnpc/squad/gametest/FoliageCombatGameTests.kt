package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.ai.diagnostics.DecisionTrace
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.combat.tactics.TacticalTask
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.tslat.smartbrainlib.util.BrainUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer
import kotlin.math.cos

/**
 * A man who loses a moving enemy in grass fires two short bursts at where he last saw him, from
 * the post he holds, and never while a firing lock holds. Each lock gets its own scene beside the
 * unlocked control, so one lock's timing cannot hide another's.
 */
@PrefixGameTestTemplate(false)
object FoliageCombatGameTests {
    /** What keeps the hidden-enemy fire from starting: none for the control. */
    enum class Lock { ALLY, WALL }

    /** Two bursts of three: LastSeenFire's whole episode. */
    private const val BLIND_ROUNDS = 6

    /** Ticks the blind fire is watched for: the episode lasts 100. */
    private const val WATCH_TICKS = 140L

    /** Unlocked: two bursts at the remembered point, from a post grass does not invalidate. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 400, batch = "foliage")
    fun foliageKeepsPhysicalPostAndAllowsOnlyTwoSafeBursts(helper: GameTestHelper) = blindFire(helper, "foliage", null)

    /** An ally in the lane to the remembered point: no blind round. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 400, batch = "foliage-ally")
    fun foliageBlindFireHoldsForAnAllyInTheLane(helper: GameTestHelper) = blindFire(helper, "foliage-ally", Lock.ALLY)

    /** A wall goes up as the enemy vanishes: the lane is gone, and so is the episode. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 400, batch = "foliage-wall")
    fun foliageBlindFireHoldsBehindAWall(helper: GameTestHelper) = blindFire(helper, "foliage-wall", Lock.WALL)

    private fun blindFire(helper: GameTestHelper, name: String, lock: Lock?) {
        val field = Field(helper)
        val shooter = field.shooter
        val enemy = field.enemy(Vec3(80.5, 1.0, 64.5))
        val ally = if (lock == Lock.ALLY) AttackDeploymentGameTests.spawn(helper, Vec3(30.5, 1.0, 10.5), SquadFaction.PIG).apply { isNoAi = true } else null
        // Noticing him in the scene's light is not what this is about: he starts on him.
        shooter.target = enemy
        val task = TacticalTask(TacticalJob.COVER, shooter.position(), enemy.position(), 1)
        var direct = 0
        var blind = 0
        var hiddenAt: Long? = null
        var aim: Vec3? = null
        var ammoBefore = 0
        // Every method his plans took: the trace keeps only his latest decisions.
        var planned = false
        field.onRound = { round ->
            if (hiddenAt == null) {
                direct++
            } else {
                blind++
                helper.assertTrue(shooter.lastSeenFire.point(helper.level.gameTime) == aim, "Blind fire followed the hidden enemy instead of the last seen point")
                val expected = aim!!.subtract(shooter.eyePosition).normalize()
                helper.assertTrue(round.deltaMovement.normalize().dot(expected) > cos(Math.toRadians(20.0)), "Real projectile was aimed away from the remembered sector")
            }
        }
        field.onClose = {
            ally?.discard()
            Files.writeString(field.report.resolve("$name.txt"), "lock=$lock direct=$direct blind=$blind\n")
        }
        helper.onEachTick {
            planned = planned || DecisionTrace.decisionsOf(shooter.uuid).any { it.event == "plan" && "*foliage_fire" in it.detail }
            if (hiddenAt == null && direct > 0) {
                aim = shooter.rememberedEye(enemy)
                helper.assertTrue(aim != null, "No personal visual memory before concealment")
                ammoBefore = Ports.guns.roundsLeft(shooter.mainHandItem)
                field.grass()
                enemy.setPos(enemy.x, enemy.y, enemy.z + 20.0)
                hiddenAt = helper.tick
                helper.assertTrue(DetectionSightline.trace(shooter, enemy) == DetectionSightline.Visibility.FOLIAGE, "Grass did not conceal the moved enemy")
                helper.assertTrue(TacticalPositions.firingReady(shooter, task, shooter.position()), "Grass invalidated a physically usable firing post")
                helper.assertTrue(!Sightline.blocked(helper.level, enemy.eyePosition, shooter.position().add(0.0, 0.9, 0.0), shooter), "Grass was mistaken for physical body cover")
                applyLock(lock, field, task, ally, aim!!)
            }
            val start = hiddenAt ?: return@onEachTick
            if (lock != null) helper.assertTrue(blind == 0, "Fired blind despite the $lock lock")
            helper.assertTrue(blind <= BLIND_ROUNDS, "Concealment refilled the finite burst budget")
            if (helper.tick - start < WATCH_TICKS) return@onEachTick
            if (lock == null) {
                helper.assertTrue(blind == BLIND_ROUNDS, "Expected two real three-round bursts, got $blind")
                helper.assertTrue(ammoBefore - Ports.guns.roundsLeft(shooter.mainHandItem) == BLIND_ROUNDS, "Blind fire did not spend exactly six real rounds")
                helper.assertTrue(shooter.lastSeenFire.point(helper.level.gameTime) == null, "Blind fire did not end")
                // The guess at a hidden target is his plan's (#189), not a behaviour's of its own.
                helper.assertTrue(planned, "His plan never chose the foliage bursts")
            }
            helper.succeed()
        }
    }

    /** Puts [lock] in place the moment the enemy vanishes. */
    private fun applyLock(lock: Lock?, field: Field, task: TacticalTask, ally: NpcEntity?, aim: Vec3) {
        val shooter = field.shooter
        val helper = field.helper
        when (lock) {
            Lock.ALLY -> ally!!.setPos(shooter.x + 10.0, shooter.y, aim.z)

            Lock.WALL -> {
                for (y in 1..3) for (z in 55..95) helper.setBlock(BlockPos(60, y, z), Blocks.STONE)
                helper.assertTrue(!TacticalPositions.firingReady(shooter, task, shooter.position()), "Physical wall failed to invalidate the firing lane")
            }

            null -> Unit
        }
    }

    /**
     * A known, visible enemy beyond discovery range keeps his aimed fire, and a hit from elsewhere
     * at the same time does not start a second, blind reply that competes with it.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 200, batch = "foliage-far")
    fun knownTargetBeyondDetectionRangeKeepsAimedFire(helper: GameTestHelper) {
        val field = Field(helper)
        val shooter = field.shooter
        val enemy = field.enemy(Vec3(140.5, 1.0, 64.5))
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(shooter.uuid))
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, shooter.blockPosition())
        var aimed = 0
        field.onRound = { aimed++ }
        field.onClose = { manager.deleteSquad(helper.level, squad.id) }
        BrainUtils.setTargetOfEntity(shooter, enemy)
        shooter.rememberVisible(enemy)
        shooter.incomingFire.record(shooter.eyePosition, null, enemy.eyePosition, helper.level.gameTime)
        shooter.incomingFire.beginReply(helper.level.gameTime)
        helper.onEachTick {
            helper.assertTrue(shooter.target === enemy, "Holding order dropped a known visible distant target")
            helper.assertTrue(shooter.distanceTo(enemy) > 72.0, "Known-target control is inside detection range")
            helper.assertTrue(shooter.incomingFire.roundsLeft == BLIND_ROUNDS, "Blind return fire competed with aimed fire at a visible distant target")
            if (helper.tick >= 60L) {
                helper.assertTrue(aimed > 0, "Known visible target beyond detection range received no aimed fire")
                helper.succeed()
            }
        }
    }

    /** A dug-in shooter facing east on a stone floor, his rounds counted, and the scene's cleanup. */
    private class Field(val helper: GameTestHelper) {
        val report: Path = Files.createDirectories(Path.of(System.getProperty("sbwnpc.gameTestReports")))
        val shooter: NpcEntity
        var onRound: (Projectile) -> Unit = {}
        var onClose: () -> Unit = {}
        private val enemies = mutableListOf<NpcEntity>()
        private val rounds = Consumer<EntityJoinLevelEvent> { event ->
            val round = (event.entity as? Projectile)?.takeIf { Ports.guns.isRound(it) }
            if (event.level === helper.level && round?.owner?.uuid == shooter.uuid) onRound(round!!)
        }

        // With nobody to see or hear it, a shot is a hitscan stand-in with no round to count
        // (OffscreenFire): a player nearby makes the gun fire real rounds.
        private val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "foliage-observer"))

        init {
            for (x in 0..159) for (z in 0..127) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
            shooter = AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 64.5), SquadFaction.PIG)
            shooter.claim(NpcActivity.DUG_IN, this)
            witness.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            val seat = helper.absoluteVec(Vec3(45.5, 5.0, 5.5))
            witness.moveTo(seat.x, seat.y, seat.z, 0.0f, 0.0f)
            witness.setNoGravity(true)
            helper.level.addNewPlayer(witness)
            // No squad paces him: his own refresh builds his engage-only plan, as the engage scenes do.
            helper.onEachTick { if (helper.tick % 10L == 0L) SquadTactics.refresh(shooter) }
            NeoForge.EVENT_BUS.addListener(rounds)
            AmmunitionTacticsGameTests.cleanup(helper) {
                onClose()
                NeoForge.EVENT_BUS.unregister(rounds)
                (enemies + shooter).forEach(Entity::discard)
                helper.level.removePlayerImmediately(witness, Entity.RemovalReason.DISCARDED)
            }
        }

        /** A still enemy who survives the scene's fire. */
        fun enemy(at: Vec3): NpcEntity =
            AttackDeploymentGameTests.spawn(helper, at, SquadFaction.COW).apply {
                isNoAi = true
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
                health = maxHealth
                enemies += this
            }

        /** Two-block grass across the lane, halfway to the enemy. */
        fun grass() {
            for (x in 50..52) {
                for (z in 45..95) {
                    helper.setBlock(BlockPos(x, 0, z), Blocks.DIRT)
                    helper.setBlock(BlockPos(x, 1, z), Blocks.TALL_GRASS)
                    helper.setBlock(BlockPos(x, 2, z), Blocks.TALL_GRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER))
                }
            }
        }
    }
}
