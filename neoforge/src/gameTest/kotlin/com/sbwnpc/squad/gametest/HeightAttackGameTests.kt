package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.tactics.TacticalFailure
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalPattern
import com.sbwnpc.squad.combat.tactics.TacticalPhase
import com.sbwnpc.squad.combat.tactics.TacticalPlan
import com.sbwnpc.squad.domain.port.Ports
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
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID
import java.util.function.Consumer

/** Full natural assessment on a long slope crossing uneven starting elevations. */
@PrefixGameTestTemplate(false)
object HeightAttackGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "height_arena", timeoutTicks = 1800, batch = "height-natural")
    fun naturalHeightAttackAdvancesAcrossValley(helper: GameTestHelper) = runScene(helper, strandMember = false)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "height_arena", timeoutTicks = 1800, batch = "height-straggler")
    fun naturalHeightAttackRecoversOneStrandedMember(helper: GameTestHelper) = runScene(helper, strandMember = true)

    private fun runScene(helper: GameTestHelper, strandMember: Boolean) {
        fun height(x: Int, z: Int) = ((x - 65) / 2).coerceIn(0, 40) + ((z - 82) / 4).coerceIn(0, 8)
        for (x in 0..175) {
            for (z in 0..223) {
                for (y in 0..height(x, z)) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
            }
        }
        val prefix = UUID.randomUUID().mostSignificantBits
        val members = (0..15).map { index ->
            val z = 66 + index * 6
            // A forward member can discover the high ground and relay it to the lower line.
            val x = if (index == 8) 95 else 65
            AttackDeploymentGameTests.spawn(helper, Vec3(x + 0.5, height(x, z) + 1.0, z + 0.5), SquadFaction.PIG, UUID(prefix, index.toLong())).apply {
                yRot = -90.0f
                yHeadRot = -90.0f
            }
        }
        var activatedAt: Long? = null
        var activationHeightAdvantage = 0.0
        var initial = emptyMap<UUID, Vec3>()
        val enemies = (0..2).map { index ->
            val z = 106 + index * 6
            AttackDeploymentGameTests.spawn(helper, Vec3(145.5, height(145, z) + 1.0, z + 0.5), SquadFaction.COW).apply {
                isNoAi = true
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
                health = maxHealth
            }
        }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, members.map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(145, height(145, 112) + 1, 112)))
        DebugFlags.enabledGroups = setOf(LogGroup.ORDER, LogGroup.PATH, LogGroup.STUCK)
        val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "height-observer"))
        witness.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
        val at = helper.absoluteVec(Vec3(100.5, 75.0, 112.5))
        witness.moveTo(at.x, at.y, at.z, 0.0f, 0.0f)
        witness.setNoGravity(true)
        helper.level.addNewPlayer(witness)
        val directory = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(directory)
        val name = if (strandMember) "height-straggler" else "height-natural"
        val csv = directory.resolve("$name.csv")
        Files.writeString(csv, "tick,npc,x,y,z,plan,pattern,phase,bounds,job,ammo,lastShot\n")
        val climbed = HashSet<UUID>()
        val firedAfterClimb = HashSet<UUID>()
        val plans = LinkedHashMap<Int, TacticalPlan>()
        var completedBounds = 0
        var stranded: UUID? = null
        var strandedPlan: Int? = null
        var recoveredStraggler = false
        val rounds = Consumer<EntityJoinLevelEvent> { event ->
            val bullet = event.entity as? Projectile
            if (event.level === helper.level && bullet != null && Ports.guns.isRound(bullet) && bullet.owner?.uuid in climbed) {
                firedAfterClimb += bullet.owner!!.uuid
            }
        }
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(rounds)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        var cleaned = false
        fun cleanup() {
            if (cleaned) return
            cleaned = true
            NeoForge.EVENT_BUS.unregister(rounds)
            NeoForge.EVENT_BUS.unregister(pacer)
            manager.deleteSquad(helper.level, squad.id)
            (members + enemies).forEach { it.discard() }
            helper.level.removePlayerImmediately(witness, Entity.RemovalReason.DISCARDED)
        }
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
        })
        fun report(result: String) {
            Files.writeString(directory.resolve("$name.txt"), "result=$result\ntick=${helper.tick}\nactivatedAt=$activatedAt\nactivationHeightAdvantage=$activationHeightAdvantage\nclimbed=${climbed.size}\nfiredAfterClimb=${firedAfterClimb.size}\nheightPlans=${plans.size}\nfailedPlans=${plans.values.count { it.failure != TacticalFailure.NONE }}\ncompletedBounds=$completedBounds\nstranded=$stranded\nstrandedPlan=$strandedPlan\nrecoveredStraggler=$recoveredStraggler\n")
        }
        helper.onEachTick {
            val plan = squad.tactics.plan
            if (plan?.pattern == TacticalPattern.ATTACK_HEIGHT) {
                if (activatedAt == null) {
                    activatedAt = helper.tick
                    initial = members.associate { it.uuid to it.position() }
                    activationHeightAdvantage = enemies.map { it.y }.average() - members.map { it.y }.average()
                    helper.assertTrue(activationHeightAdvantage >= 20.0, "Fixture activated too late to exercise a large height difference: $activationHeightAdvantage")
                }
                plans[plan.id] = plan
                completedBounds = maxOf(completedBounds, plan.bounds)
                if (strandMember && stranded == null && plan.phase == TacticalPhase.EXECUTING) {
                    val npc = members.firstOrNull { plan.tasks[it.uuid]?.job == TacticalJob.ADVANCE }
                    if (npc != null) {
                        stranded = npc.uuid
                        strandedPlan = plan.id
                        npc.isNoAi = true
                        npc.navigation.stop()
                        npc.deltaMovement = Vec3.ZERO
                    }
                }
                if (plan.id == strandedPlan && stranded in plan.failedMembers && plan.bounds >= 2) recoveredStraggler = true
            }
            for (npc in members) {
                val start = initial[npc.uuid]
                if (start != null && npc.x - start.x >= 14.0 && npc.y - start.y >= 7.0) climbed += npc.uuid
                val closest = enemies.minOf { npc.position().subtract(it.position()).horizontalDistance() }
                helper.assertTrue(closest >= 47.25, "Height attacker crossed enemy path clearance: $closest")
                if (helper.tick % 10 == 0L) {
                    val p = helper.relativeVec(npc.position())
                    Files.writeString(csv, "${helper.tick},${npc.uuid},${p.x},${p.y},${p.z},${plan?.id},${plan?.pattern},${plan?.phase},${plan?.bounds},${plan?.tasks?.get(npc.uuid)?.job},${Ports.guns.roundsLeft(npc.mainHandItem)},${npc.lastShotTick}\n", APPEND)
                }
            }
            if (completedBounds >= 2 && climbed.size >= 10 && firedAfterClimb.size >= 8 && (!strandMember || recoveredStraggler)) {
                report("PASS")
                helper.succeed()
            }
        }
        helper.runAtTickTime(1799) {
            report("FAIL")
            helper.fail("Natural height attack did not advance and fire with most members; see $name.csv")
        }
    }
}
