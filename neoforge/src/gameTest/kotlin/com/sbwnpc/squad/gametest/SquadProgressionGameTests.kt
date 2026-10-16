package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.network.buildSquadDetailsSnapshot
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.squad.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import kotlin.math.abs

@PrefixGameTestTemplate(false)
object SquadProgressionGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "progression-legacy")
    fun loadedLegacySquadsResolveTheirRolesBeforeMerging(helper: GameTestHelper) {
        floor(helper)
        val manager = SquadManager.get(helper.level)
        val owner = UUID.randomUUID()
        val sniper = spawn(helper, 3.5, 5.5).apply { npcClass = NpcClass.SNIPER }
        val medic = spawn(helper, 7.5, 5.5).apply { npcClass = NpcClass.MEDIC }
        val from = manager.create(helper.level, owner, SquadFaction.PIG, listOf(sniper.uuid))
        val into = manager.create(helper.level, owner, SquadFaction.PIG, listOf(medic.uuid))
        from.recruitmentRoster = null
        into.recruitmentRoster = null
        cleanup(helper) {
            manager.disband(helper.level, from.id)
            manager.disband(helper.level, into.id)
            listOf(sniper, medic).forEach { it.discard() }
        }
        helper.runAtTickTime(5) {
            val snapshot = buildSquadDetailsSnapshot(manager, helper.level.server, owner, from.id)!!
            helper.assertTrue(snapshot.getList("MergeCandidates", Tag.TAG_COMPOUND.toInt()).size == 1, "Loaded legacy roles never made a legal merge destination")
            // Also cover a direct command rather than relying on the screen's preparation.
            from.recruitmentRoster = null
            helper.assertTrue(manager.merge(helper.level, from.id, into.id, owner) == null, "Direct legacy merge rejected")
            helper.assertTrue(into.originalComposition == listOf(NpcClass.MEDIC, NpcClass.SNIPER), "Legacy roles were guessed or lost")
            helper.assertTrue(sniper.squadId == into.id && medic.squadId == into.id, "Legacy entities lost membership")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 600, batch = "progression-merge")
    fun mergedSoldiersKeepIdentityAcrossUnloadAndFollowReceiverOrders(helper: GameTestHelper) {
        for (x in 0 until 160) for (z in 0 until 128) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val owner = UUID.randomUUID()
        val moved = spawn(helper, 3.5, 58.5)
        val sleeping = spawn(helper, 5.5, 64.5)
        val receiver = spawn(helper, 7.5, 70.5)
        val manager = SquadManager.get(helper.level)
        val source = manager.create(helper.level, owner, SquadFaction.PIG, listOf(moved.uuid, sleeping.uuid))
        val destination = manager.create(helper.level, owner, SquadFaction.PIG, listOf(receiver.uuid))
        manager.setOrder(destination.id, SquadOrder.MOVE)
        val goal = helper.absolutePos(BlockPos(19, 1, 64))
        manager.setObjective(helper.level, destination.id, goal)
        sleeping.gainExperience(37.0, "test_existing_veterancy")
        repeat(3) { Ports.guns.inHand(sleeping)!!.spendShot() }
        val rounds = Ports.guns.roundsLeft(sleeping.mainHandItem)
        val identity = sleeping.name.string
        val xp = sleeping.experience
        val saved = CompoundTag()
        sleeping.saveWithoutId(saved)
        val original = listOf(moved.position(), sleeping.position(), receiver.position())
        var restored: NpcEntity? = null
        var enemy: NpcEntity? = null
        var advanced = false
        val trace = StringBuilder("tick,advanced,actor,x,y,z,done,target,shots,order,health\n")
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(pacer)
        cleanup(helper) {
            NeoForge.EVENT_BUS.unregister(pacer)
            manager.disband(helper.level, destination.id)
            manager.disband(helper.level, source.id)
            listOfNotNull(moved, sleeping, receiver, restored, enemy).forEach { it.discard() }
            val report = java.nio.file.Path.of(System.getProperty("sbwnpc.gameTestReports"), "merge-actors.csv")
            java.nio.file.Files.createDirectories(report.parent)
            java.nio.file.Files.writeString(report, trace)
        }
        helper.runAtTickTime(5) { sleeping.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK) }
        helper.runAtTickTime(7) {
            val snapshot = buildSquadDetailsSnapshot(manager, helper.level.server, owner, source.id)!!
            val unloaded = snapshot.getList("Members", Tag.TAG_COMPOUND.toInt()).map { it as CompoundTag }.first { it.getUUID("Id") == sleeping.uuid }
            helper.assertTrue(!unloaded.getBoolean("Loaded") && !unloaded.contains("Experience"), "Details fabricated an offline soldier profile")
            helper.assertTrue(buildSquadDetailsSnapshot(manager, helper.level.server, UUID.randomUUID(), source.id) == null, "Details exposed somebody else's squad")
            helper.assertTrue(manager.merge(helper.level, source.id, destination.id, owner) == null, "Legal merge rejected")
            helper.assertTrue(manager.get(source.id) == null && destination.members.size == 3, "Merge did not transfer the full roster")
            helper.assertTrue(destination.order == SquadOrder.MOVE && destination.objective == goal, "Merge replaced receiver commands")
            manager.creditExperience(helper.level.server, sleeping.uuid, 10.0, "test_in_flight_assist")
        }
        helper.runAtTickTime(10) {
            restored = ModEntities.NPC.get().create(helper.level)!!.apply {
                load(saved)
                helper.assertTrue(helper.level.addFreshEntity(this), "Unloaded soldier could not return")
            }
            val npc = restored!!
            helper.assertTrue(npc.squadId == destination.id && npc.name.string == identity && npc.npcRank == sleeping.npcRank, "Late load restored the removed squad or lost identity")
            helper.assertTrue(abs(npc.experience - xp - 10.0) < 1e-6 && Ports.guns.roundsLeft(npc.mainHandItem) == rounds, "Late load lost XP or reset ammunition")
            listOf(moved, npc, receiver).forEach { it.isNoAi = false }
        }
        helper.onEachTick {
            val loaded = restored ?: return@onEachTick
            val actors = listOf(moved, loaded, receiver)
            if (helper.tick % 10L == 0L) {
                actors.forEachIndexed { index, actor ->
                    val pos = helper.relativeVec(actor.position())
                    trace.append("${helper.tick},$advanced,$index,${pos.x},${pos.y},${pos.z},${actor.navigation.isDone},${actor.target?.uuid},${actor.lastShotTick},${destination.order},${actor.health}\n")
                }
            }

            if (!advanced && actors.zip(original).all { (npc, start) -> npc.x - start.x > 7 }) {
                advanced = true
                enemy = AttackDeploymentGameTests.spawn(helper, Vec3(40.5, 1.0, 64.5), SquadFaction.COW).apply {
                    isNoAi = true
                    getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)!!.baseValue = 200.0
                    health = maxHealth
                }
                manager.issueOrder(destination.id, SquadOrder.ATTACK)
                manager.setObjective(helper.level, destination.id, enemy!!.blockPosition())
            }
            if (advanced && actors.all { it.lastShotTick >= 0 }) {
                helper.assertTrue(actors.all { it.currentSquad()?.id == destination.id }, "Actor lost receiver membership during combat")
                helper.assertTrue(enemy!!.health < enemy!!.maxHealth, "Merged squad never hit the enemy")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 50, batch = "progression-recruit")
    fun deploymentAndBarracksRecruitFreshNamesAtRecruitRank(helper: GameTestHelper) {
        floor(helper)
        val manager = SquadManager.get(helper.level)
        val owner = UUID.randomUUID()
        val config = SquadToolItem.Config(NpcClass.SNIPER, NpcRank.ELITE, SquadFaction.PIG, SquadPreset.SINGLE)
        val barracks = BarracksRef(helper.level.dimension(), helper.absolutePos(BlockPos(16, 0, 6)))
        val group = manager.createAtBarracks(helper.level, barracks, owner, config)
        val slot = group.recruitmentRoster!!.slots.single()
        var deployed: SquadDeployment.Result? = null
        cleanup(helper) {
            manager.deleteSquad(helper.level, group.id)
            deployed?.members?.forEach { it.discard() }
        }
        helper.runAtTickTime(5) {
            helper.assertTrue(manager.recruitAtBarracks(helper.level, barracks, BarracksRecruitmentQueue.Request(group.id, slot.id, false)) == null, "Barracks recruitment failed")
            val recruit = helper.level.getEntity(group.members.single()) as NpcEntity
            helper.assertTrue(recruit.npcRank == NpcRank.RECRUIT && recruit.experience == 0.0 && recruit.customName != null, "Barracks copied selectable legacy rank instead of recruiting a new soldier")
            deployed = SquadDeployment.deploy(helper.level, helper.absolutePos(BlockPos(5, 1, 6)), 0f, config, owner)
            helper.assertTrue(deployed?.members?.single()?.npcRank == NpcRank.RECRUIT && deployed!!.members.single().experience == 0.0, "Tool deployment still lets an old config spawn elites")
            helper.succeed()
        }
    }

    private fun spawn(helper: GameTestHelper, x: Double, z: Double) = AttackDeploymentGameTests.spawn(helper, Vec3(x, 1.0, z), SquadFaction.PIG).apply { isNoAi = true }
    private fun floor(helper: GameTestHelper) {
        for (x in 0 until 24) for (z in 0 until 12) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
    }
    private fun cleanup(helper: GameTestHelper, action: () -> Unit) {
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = action()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = action()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = action()
        })
    }
}
