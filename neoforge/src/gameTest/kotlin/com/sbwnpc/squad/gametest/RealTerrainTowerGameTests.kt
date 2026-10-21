package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.GameType
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The owner's scaffold tower on the terrain round it, restored from his save (playtest 20.10):
 * there, no support went up it on DEFEND, and every deck it tried was refused as unreachable.
 * Needs the save, so it is registered with the real-terrain battles ([RealTerrainBattleGameTests]).
 */
@PrefixGameTestTemplate(false)
object RealTerrainTowerGameTests {
    private const val WORLD = "New World"
    private val OBJECTIVE = BlockPos(-234, 91, 776)
    private const val RADIUS = 48

    /** The tower's two decks, feet height 97 and 102. */
    private val DECKS = AABB(-238.0, 96.5, 781.0, -233.0, 103.0, 786.0)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 2000, batch = "real-tower")
    fun supportsClimbTheOwnersTowerOnItsOwnTerrain(helper: GameTestHelper) {
        val level = helper.level
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports")).resolve("real-tower")
        Files.createDirectories(report)
        val replay = SavedBattleReplay(level, RealTerrainBattleGameTests.copyRegions(Path.of(System.getProperty("sbwnpc.gameTestBattleSaves")).resolve(WORLD), report.resolve("fixture"), OBJECTIVE, RADIUS))
        replay.restoreTerrain(OBJECTIVE, RADIUS)
        level.getEntitiesOfClass(NpcEntity::class.java, AABB(OBJECTIVE).inflate(RADIUS.toDouble(), 128.0, RADIUS.toDouble())).forEach(Entity::discard)
        val previousGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = previousGroups + setOf(LogGroup.ORDER, LogGroup.PATH)
        // Where the squad stood in the playtest before it was sent to the tower.
        val roles = listOf(NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.SNIPER, NpcClass.SNIPER, NpcClass.MACHINE_GUNNER, NpcClass.MACHINE_GUNNER, NpcClass.MEDIC)
        val men = roles.mapIndexed { index, role ->
            val x = -214.5 + index % 4 * 2
            val z = 794.5 + index / 4 * 2
            ModEntities.NPC.get().create(level)!!.apply {
                moveTo(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt()).toDouble(), z, 0f, 0f)
                npcClass = role
                spawnFaction = SquadFaction.CAT
                finalizeSpawn(level, level.getCurrentDifficultyAt(blockPosition()), MobSpawnType.COMMAND, null)
                SquadTeams.assign(this, SquadFaction.CAT)
                check(level.addFreshEntity(this)) { "Tower NPC could not be added" }
            }
        }
        // Entities far from the test structure tick only near a player.
        val observer = FakePlayerFactory.get(level, GameProfile(UUID.nameUUIDFromBytes("real-tower-observer".toByteArray()), "real-tower-observer")).apply {
            gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            moveTo(OBJECTIVE.x + 0.5, OBJECTIVE.y + 32.0, OBJECTIVE.z + 0.5, 0f, 0f)
            setNoGravity(true)
            level.addNewPlayer(this)
        }
        val manager = SquadManager.get(level)
        val squad = manager.create(level, UUID.nameUUIDFromBytes("real-tower".toByteArray()), SquadFaction.CAT, men.map { it.uuid })
        // Just added in a chunk the replay forced, they are not found by UUID yet, so create() could not tag them.
        men.forEach { it.squadId = squad.id }
        var closed = false
        fun cleanup() {
            if (closed) return
            closed = true
            manager.deleteSquad(level, squad.id)
            men.forEach(Entity::discard)
            level.removePlayerImmediately(observer, Entity.RemovalReason.DISCARDED)
            replay.close()
            DebugFlags.enabledGroups = previousGroups
        }
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
        })
        manager.issueOrderAt(level, squad.id, SquadOrder.DEFEND, OBJECTIVE)
        val supports = men.filter { it.npcClass == NpcClass.SNIPER || it.npcClass == NpcClass.MACHINE_GUNNER }
        val trace = report.resolve("result.txt")
        helper.onEachTick {
            val up = supports.filter { npc ->
                val post = NativeTactics.assignment(npc)?.position
                post != null && DECKS.contains(post) && npc.onGround() && npc.position().distanceTo(post) <= 0.7
            }
            if (helper.tick % 100L == 0L) {
                Files.writeString(trace, "tick=${helper.tick} up=${up.size} supports=${supports.map { "${it.npcClass}@${it.blockPosition().toShortString()} post=${NativeTactics.assignment(it)?.position}" }}\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
            }
            if (up.isNotEmpty()) {
                Files.writeString(trace, "PASS tick=${helper.tick} up=${up.map { it.position() }}\n", java.nio.file.StandardOpenOption.APPEND)
                helper.succeed()
            }
        }
    }
}
