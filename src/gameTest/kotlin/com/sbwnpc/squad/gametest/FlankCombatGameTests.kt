package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.tactics.FlankingState
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalPattern
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

/** Acceptance uses the normal selector, brain and real projectiles, with no invented readiness. */
@PrefixGameTestTemplate(false)
object FlankCombatGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "flank-combat")
    fun squadCompletesFlankAndFiresFromItsSide(helper: GameTestHelper) = runScene(helper, 8, uneven = false)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "flank-terrain")
    fun largeSquadCompletesBothFlanksOnUnevenGround(helper: GameTestHelper) = runScene(helper, 16, uneven = true)

    private fun runScene(helper: GameTestHelper, count: Int, uneven: Boolean) {
        for (x in 0..175) for (z in 0..223) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        if (uneven) {
            for (x in 38..56) {
                for (z in 50..180) {
                    if ((x / 4 + z / 9) % 4 == 0) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
                }
            }
        }
        val members = (0 until count).map { AttackDeploymentGameTests.spawn(helper, Vec3(30.5, 1.0, 80.5 + it * 6), SquadFaction.PIG) }
        val enemies = (0..2).map {
            AttackDeploymentGameTests.spawn(helper, Vec3(100.5, 1.0, 98.5 + it * 6), SquadFaction.COW).apply {
                isNoAi = true
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
                health = maxHealth
            }
        }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, members.map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(100, 1, 104)))
        val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "flank-observer"))
        witness.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
        val at = helper.absoluteVec(Vec3(70.5, 8.0, 104.5))
        witness.moveTo(at.x, at.y, at.z, 0.0f, 0.0f)
        witness.setNoGravity(true)
        helper.level.addNewPlayer(witness)
        val directory = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(directory)
        val name = if (uneven) "flank-combat-large-terrain" else "flank-combat"
        val csv = directory.resolve("$name.csv")
        Files.writeString(csv, "tick,npc,x,y,z,plan,pattern,phase,job,pathDone,ammo,lastShot,arrived\n")
        val arrived = HashSet<UUID>()
        val firedFromFlank = HashSet<UUID>()
        val launched = HashSet<UUID>()
        val wings = HashSet<Int>()
        val required = count - count / 3
        var completed = false
        val bullets = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile
            if (event.level === helper.level && round != null && Ports.guns.isRound(round) && round.owner?.uuid in arrived) {
                firedFromFlank += round.owner!!.uuid
            }
        }
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(bullets)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        var cleaned = false
        fun cleanup() {
            if (cleaned) return
            cleaned = true
            NeoForge.EVENT_BUS.unregister(bullets)
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
        helper.onEachTick {
            val plan = squad.tactics.plan
            if (plan?.pattern == TacticalPattern.FLANK) {
                launched += plan.tasks.filterValues { it.job == TacticalJob.FLANK }.keys
                val state = plan.behavior as FlankingState
                arrived += state.arrived
                for (npc in members.filter { it.uuid in state.arrived }) {
                    val projection = npc.position().subtract(plan.focus!!).dot(plan.frontage.side)
                    if (kotlin.math.abs(projection) >= 16.0) wings += if (projection < 0) -1 else 1
                }
                if (state.arrived.size >= required) completed = true
            }
            for (npc in members) {
                helper.assertTrue(enemies.all { npc.position().subtract(it.position()).horizontalDistance() >= 47.9 }, "Flanker crossed enemy movement clearance")
            }
            if (helper.tick % 10 == 0L) {
                for (npc in members) {
                    val point = helper.relativeVec(npc.position())
                    Files.writeString(csv, "${helper.tick},${npc.uuid},${point.x},${point.y},${point.z},${plan?.id},${plan?.pattern},${plan?.phase},${plan?.tasks?.get(npc.uuid)?.job},${npc.navigation.isDone},${Ports.guns.roundsLeft(npc.mainHandItem)},${npc.lastShotTick},${npc.uuid in arrived}\n", APPEND)
                }
            }
            if (completed && firedFromFlank.size >= required && (!uneven || wings.size == 2)) {
                Files.writeString(directory.resolve("$name.txt"), "result=PASS\ntick=${helper.tick}\nlaunched=${launched.size}\narrived=${arrived.size}\nfiredFromFlank=${firedFromFlank.size}\nwings=$wings\n")
                helper.succeed()
            }
        }
        helper.runAtTickTime(1199) {
            Files.writeString(directory.resolve("$name.txt"), "result=FAIL\nlaunched=${launched.size}\narrived=${arrived.size}\nfiredFromFlank=${firedFromFlank.size}\nwings=$wings\nplan=${squad.tactics.plan?.pattern}\nphase=${squad.tactics.plan?.phase}\n")
            helper.fail("Flank did not finish and fire: launched=${launched.size}, arrived=${arrived.size}, fired=${firedFromFlank.size}; see $name.csv")
        }
    }
}
