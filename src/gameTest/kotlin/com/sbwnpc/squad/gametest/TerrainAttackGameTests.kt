package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
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
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID
import java.util.function.Consumer

/** A few exposed scouts see the enemy, while the ridge blocks most of an already spread line. */
@GameTestHolder("sbwnpc")
@PrefixGameTestTemplate(false)
object TerrainAttackGameTests {
    @JvmStatic
    @GameTest(template = "terrain_arena", timeoutTicks = 1200, batch = "terrain")
    fun attackPastRidgeWithoutReturnFire(helper: GameTestHelper) = ridge(helper, returnFire = false)

    @JvmStatic
    @GameTest(template = "terrain_arena", timeoutTicks = 1200, batch = "terrain-return-fire")
    fun attackPastRidgeUnderFire(helper: GameTestHelper) = ridge(helper, returnFire = true)

    private fun ridge(helper: GameTestHelper, returnFire: Boolean) {
        for (x in 0..175) for (z in 0..223) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        for (x in 34..78) {
            for (z in 0..198) {
                val height = minOf(6, (x - 34) / 3 + 1, (78 - x) / 3 + 1)
                for (y in 1..height) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
            }
        }
        for (y in 1..9) helper.setBlock(BlockPos(30, y, 112), Blocks.STONE)
        val members = (0..31).map { index ->
            AttackDeploymentGameTests.spawn(helper, Vec3(30.5, if (index == 16) 10.0 else 1.0, 16.5 + index * 6), SquadFaction.PIG)
        }
        val enemies = (0..3).map { index ->
            AttackDeploymentGameTests.spawn(helper, Vec3(100.5, 1.0, 92.5 + index * 6), SquadFaction.COW).apply {
                isNoAi = !returnFire
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
                health = maxHealth
            }
        }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, members.map { it.uuid })
        val defenders = if (returnFire) manager.create(helper.level, UUID.randomUUID(), SquadFaction.COW, enemies.map { it.uuid }) else null
        if (defenders != null) {
            manager.setOrder(defenders.id, SquadOrder.DEFEND)
            manager.setObjective(helper.level, defenders.id, helper.absolutePos(BlockPos(100, 1, 101)))
        }
        val observer = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "terrain-observer"))
        observer.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
        val at = helper.absoluteVec(Vec3(70.5, 8.0, 110.5))
        observer.moveTo(at.x, at.y, at.z, 0.0f, 0.0f)
        observer.setNoGravity(true)
        helper.level.addNewPlayer(observer)
        val name = if (returnFire) "ridge-under-fire" else "ridge-no-fire"
        val directory = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(directory)
        val csv = directory.resolve("$name.csv")
        Files.writeString(csv, "tick,npc,x,y,z,onGround,pathDone,job,plan,phase,ammo,lastShotTick\n")
        val shooters = HashSet<UUID>()
        val advanced = HashSet<UUID>()
        var enemyRounds = 0
        val bullets = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile
            if (event.level === helper.level && round != null && Ports.guns.isRound(round)) {
                val owner = round.owner?.uuid
                if (members.any { it.uuid == owner }) shooters.add(owner!!)
                if (enemies.any { it.uuid == owner }) enemyRounds++
            }
        }
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(bullets)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        DebugFlags.enabledGroups = setOf(LogGroup.ORDER, LogGroup.PATH, LogGroup.STUCK)
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(100, 1, 101)))
        helper.onEachTick {
            for (npc in members) {
                val p = helper.relativeVec(npc.position())
                if (p.x >= 42.0 && npc.onGround()) advanced.add(npc.uuid)
                if (helper.tick % 10 == 0L) {
                    val plan = squad.tactics.plan
                    Files.writeString(csv, "${helper.tick},${npc.uuid},${p.x},${p.y},${p.z},${npc.onGround()},${npc.navigation.isDone},${plan?.tasks?.get(npc.uuid)?.job},${plan?.id},${plan?.phase},${Ports.guns.roundsLeft(npc.mainHandItem)},${npc.lastShotTick}\n", APPEND)
                }
            }
        }
        helper.runAtTickTime(1199) {
            try {
                Files.writeString(directory.resolve("$name.txt"), "shooters=${shooters.size}\nadvanced=${advanced.size}\nalive=${members.count { it.isAlive }}\nenemyRounds=$enemyRounds\n")
                helper.assertTrue(!returnFire || enemyRounds > 0, "Return-fire control did not fire real rounds")
                helper.assertTrue(returnFire || enemyRounds == 0, "Isolation control fired unexpectedly")
                helper.assertTrue(advanced.size >= if (returnFire) 12 else 24, "Attack failed to advance past the ridge; see $name.csv")
                helper.assertTrue(shooters.size >= if (returnFire) 12 else 24, "Most of the attack never fired real rounds; see $name.txt")
                helper.assertTrue(returnFire || members.all { it.isAlive }, "Attack lost NPCs without return fire")
                helper.succeed()
            } finally {
                NeoForge.EVENT_BUS.unregister(bullets)
                NeoForge.EVENT_BUS.unregister(pacer)
                manager.deleteSquad(helper.level, squad.id)
                if (defenders != null) manager.deleteSquad(helper.level, defenders.id)
                (members + enemies).forEach { it.discard() }
                helper.level.removePlayerImmediately(observer, Entity.RemovalReason.DISCARDED)
            }
        }
    }
}
