package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.combat.tactics.TacticalTask
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
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
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.tslat.smartbrainlib.util.BrainUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer

/** A normal NPC brain loses a moving enemy in grass; the real weapon must obey every firing lock. */
@PrefixGameTestTemplate(false)
object FoliageCombatGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 400, batch = "foliage")
    fun foliageKeepsPhysicalPostAndAllowsOnlyTwoSafeBursts(helper: GameTestHelper) {
        for (x in 0..159) for (z in 0..127) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val shooter = AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 64.5), SquadFaction.PIG).apply { claim(NpcActivity.DUG_IN, this@FoliageCombatGameTests) }
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(80.5, 1.0, 64.5), SquadFaction.COW).apply {
            isNoAi = true
            getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
            health = maxHealth
        }
        val ally = AttackDeploymentGameTests.spawn(helper, Vec3(30.5, 1.0, 10.5), SquadFaction.PIG).apply { isNoAi = true }
        val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "foliage-observer"))
        witness.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
        val observerPos = helper.absoluteVec(Vec3(45.5, 5.0, 5.5))
        witness.moveTo(observerPos.x, observerPos.y, observerPos.z, 0.0f, 0.0f)
        witness.setNoGravity(true)
        helper.level.addNewPlayer(witness)
        val task = TacticalTask(TacticalJob.COVER, shooter.position(), enemy.position(), 1)
        var directRounds = 0
        var hiddenRounds = 0
        var hiddenAt: Long? = null
        var farPhaseAt: Long? = null
        var farDirectBefore = 0
        var squadId: UUID? = null
        var aim: Vec3? = null
        var ammoBefore = 0
        var lock = ""
        var lockUntil = 0L
        var lockRounds = 0
        val locksPassed = mutableSetOf<String>()
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"), "foliage-fire.txt")
        val projectiles = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile
            if (event.level === helper.level && round?.owner === shooter && Ports.guns.isRound(round)) {
                if (hiddenAt == null || farPhaseAt != null) {
                    directRounds++
                } else {
                    hiddenRounds++
                    helper.assertTrue(shooter.lastSeenFire.point(helper.level.gameTime) == aim, "Blind fire followed the hidden enemy instead of the last seen point")
                    val expected = aim!!.subtract(shooter.eyePosition).normalize()
                    helper.assertTrue(round.deltaMovement.normalize().dot(expected) > kotlin.math.cos(Math.toRadians(20.0)), "Real projectile was aimed away from the remembered sector")
                }
            }
        }
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(projectiles)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                Files.createDirectories(report.parent)
                Files.writeString(report, "directRounds=$directRounds\nhiddenRounds=$hiddenRounds\nlocksPassed=$locksPassed\nfarKnownRounds=${directRounds - farDirectBefore}\nincomingRoundsLeft=${shooter.incomingFire.roundsLeft}\n")
                NeoForge.EVENT_BUS.unregister(projectiles)
                NeoForge.EVENT_BUS.unregister(pacer)
                squadId?.let { SquadManager.get(helper.level).deleteSquad(helper.level, it) }
                listOf(shooter, enemy, ally).forEach { it.discard() }
                helper.level.removePlayerImmediately(witness, Entity.RemovalReason.DISCARDED)
            }
        })

        fun stoneWall(block: net.minecraft.world.level.block.Block) {
            for (y in 1..3) for (z in 55..95) helper.setBlock(BlockPos(60, y, z), block)
        }

        helper.onEachTick {
            val farStart = farPhaseAt
            if (farStart != null) {
                // A known target may be aimed at beyond discovery range. An incoming-fire episode
                // must not create a second shooter while that target is personally visible.
                helper.assertTrue(shooter.target === enemy, "Holding order dropped a known visible distant target")
                helper.assertTrue(shooter.distanceTo(enemy) > 72.0, "Known-target control is inside detection range")
                helper.assertTrue(shooter.incomingFire.roundsLeft == 6, "Blind return fire competed with aimed fire at a visible distant target")
                if (helper.tick - farStart >= 60) {
                    helper.assertTrue(directRounds > farDirectBefore, "Known visible target beyond detection range received no aimed fire")
                    helper.succeed()
                }
                return@onEachTick
            }
            if (hiddenAt == null && directRounds > 0) {
                aim = shooter.rememberedEye(enemy)
                helper.assertTrue(aim != null, "No personal visual memory before concealment")
                ammoBefore = Ports.guns.roundsLeft(shooter.mainHandItem)
                for (x in 50..52) {
                    for (z in 45..95) {
                        helper.setBlock(BlockPos(x, 0, z), Blocks.DIRT)
                        helper.setBlock(BlockPos(x, 1, z), Blocks.TALL_GRASS)
                        helper.setBlock(BlockPos(x, 2, z), Blocks.TALL_GRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER))
                    }
                }
                enemy.setPos(enemy.x, enemy.y, enemy.z + 20.0)
                hiddenAt = helper.tick
                helper.assertTrue(DetectionSightline.trace(shooter, enemy) == DetectionSightline.Visibility.FOLIAGE, "Grass did not conceal the moved enemy")
                helper.assertTrue(TacticalPositions.firingReady(shooter, task, shooter.position()), "Grass invalidated a physically usable firing post")
                helper.assertTrue(!Sightline.blocked(helper.level, enemy.eyePosition, shooter.position().add(0.0, 0.9, 0.0), shooter), "Grass was mistaken for physical body cover")
            }
            val start = hiddenAt ?: return@onEachTick
            if (lock.isNotEmpty()) {
                helper.assertTrue(hiddenRounds == lockRounds, "Fired despite $lock lock")
                if (helper.tick < lockUntil) return@onEachTick
                locksPassed += lock
                when (lock) {
                    "ally" -> ally.setPos(ally.x, ally.y, helper.absoluteVec(Vec3(0.0, 0.0, 10.5)).z)
                    "wall" -> stoneWall(Blocks.AIR)
                    "supply" -> shooter.release(NpcActivity.RESUPPLY, this)
                }
                lock = ""
            }
            val nextLock = when {
                hiddenRounds >= 1 && "ally" !in locksPassed -> "ally"
                hiddenRounds >= 3 && "wall" !in locksPassed -> "wall"
                hiddenRounds >= 4 && "supply" !in locksPassed -> "supply"
                else -> null
            }
            if (nextLock != null) {
                lock = nextLock
                lockRounds = hiddenRounds
                lockUntil = helper.tick + 12
                when (lock) {
                    "ally" -> ally.setPos(shooter.x + 10.0, shooter.y, aim!!.z)

                    "wall" -> {
                        stoneWall(Blocks.STONE)
                        helper.assertTrue(!TacticalPositions.firingReady(shooter, task, shooter.position()), "Physical wall failed to invalidate the firing lane")
                    }

                    "supply" -> shooter.claim(NpcActivity.RESUPPLY, this)
                }
            }
            helper.assertTrue(hiddenRounds <= 6, "Concealment refilled the finite burst budget")
            if (helper.tick - start >= 140) {
                helper.assertTrue(hiddenRounds == 6, "Expected two real three-round bursts, got $hiddenRounds")
                helper.assertTrue(locksPassed == setOf("ally", "wall", "supply"), "Not all firing locks were exercised")
                helper.assertTrue(ammoBefore - Ports.guns.roundsLeft(shooter.mainHandItem) == 6, "Blind fire did not spend exactly six real rounds")
                helper.assertTrue(shooter.lastSeenFire.point(helper.level.gameTime) == null, "Blind fire did not end")
                farPhaseAt = helper.tick
                farDirectBefore = directRounds
                for (x in 50..52) {
                    for (z in 45..95) {
                        helper.setBlock(BlockPos(x, 2, z), Blocks.AIR)
                        helper.setBlock(BlockPos(x, 1, z), Blocks.AIR)
                    }
                }
                val farPosition = helper.absoluteVec(Vec3(140.5, 1.0, 64.5))
                enemy.setPos(farPosition.x, farPosition.y, farPosition.z)
                val manager = SquadManager.get(helper.level)
                val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(shooter.uuid))
                squadId = squad.id
                manager.setOrder(squad.id, SquadOrder.DEFEND)
                manager.setObjective(helper.level, squad.id, shooter.blockPosition())
                BrainUtils.setTargetOfEntity(shooter, enemy)
                shooter.incomingFire.record(shooter.eyePosition, null, enemy.eyePosition, helper.level.gameTime)
                shooter.incomingFire.beginReply(helper.level.gameTime)
            }
        }
    }
}
