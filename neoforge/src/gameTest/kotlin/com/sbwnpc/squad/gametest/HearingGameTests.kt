package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.value.AttachmentType
import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.Alarm
import com.sbwnpc.squad.combat.Hearing
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.PlayerFactionRegistry
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.tslat.smartbrainlib.util.BrainUtils
import java.util.UUID

@PrefixGameTestTemplate(false)
object HearingGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "player-shot-interest")
    fun playerGunshotsInterestBothSidesWithoutMakingAlliedFootstepsThreats(helper: GameTestHelper) {
        for (x in 0..23) for (z in 0..11) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val ally = AttackDeploymentGameTests.spawn(helper, Vec3(7.5, 1.0, 6.5), SquadFaction.PIG).apply { isNoAi = true }
        val hostile = AttackDeploymentGameTests.spawn(helper, Vec3(13.5, 1.0, 6.5), SquadFaction.COW).apply { isNoAi = true }
        val player = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "shot-interest"))
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL)
        player.setPos(helper.absoluteVec(Vec3(10.5, 1.0, 6.5)))
        PlayerFactionRegistry.get(helper.level).set(player.uuid, SquadFaction.PIG)
        cleanup(helper) { listOf(ally, hostile).forEach { it.discard() } }
        helper.runAtTickTime(5) {
            Hearing.footstep(helper.level, player, landing = true)
            helper.assertTrue(!ally.listening() && hostile.listening(), "Allied footsteps became a threat or hostile footsteps stopped working")
            hostile.clearAlert()
            Hearing.gunshot(helper.level, player, 20.0, suppressed = false)
            helper.assertTrue(listOf(ally, hostile).all { it.listening() && it.isAlert() && it.target == null }, "Player shot did not interest both sides without granting targets")
            helper.assertTrue(BrainUtils.getMemory(ally, ModMemories.ALERT_POSITION.get()) == player.eyePosition, "Allied investigation did not use the player's shot location")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 40, batch = "hearing")
    fun gunshotsReachExtendedRingWithoutGrantingTargets(helper: GameTestHelper) {
        for (x in 0..159) for (z in 0..127) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val actors = mutableListOf<NpcEntity>()
        fun actor(x: Double, faction: SquadFaction): NpcEntity =
            AttackDeploymentGameTests.spawn(helper, Vec3(x, 1.0, 64.5), faction).apply {
                isNoAi = true
                actors += this
            }
        val shooter = actor(10.5, SquadFaction.PIG)
        shooter.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, Ports.guns.issue(ResourceLocation.fromNamespaceAndPath("superbwarfare", "ak_12"), shooter, 100))
        val extended = actor(55.5, SquadFaction.COW)
        val outside = actor(70.5, SquadFaction.COW)
        val ally = actor(80.5, SquadFaction.PIG)
        val outsideAlly = actor(81.5, SquadFaction.PIG)
        cleanup(helper) { actors.forEach { it.discard() } }
        helper.runAtTickTime(1) {
            // AK-12: formerly 36 blocks; the 45-block listener must now hear, the 60-block one must not.
            Ports.guns.inHand(shooter)!!.let { Hearing.gunshot(helper.level, shooter, it.hearingRadius, it.suppressed) }
            helper.assertTrue(extended.listening() && extended.isAlert(), "Hostile listener outside the old gunshot radius did not hear")
            helper.assertTrue(extended.target == null, "Hearing granted an unseen firing target")
            helper.assertTrue(!outside.listening(), "Gunshot leaked outside the new radius")
            helper.assertTrue(!ally.listening(), "Hostile-noise handling treated an ally as an enemy")
            val threat = helper.absoluteVec(Vec3(90.5, 1.0, 64.5))
            Alarm.raise(shooter, shooter.position(), threat, Hearing.ALLIED_GUNSHOT_RADIUS)
            helper.assertTrue(BrainUtils.getMemory(ally, ModMemories.ALERT_POSITION.get()) == threat, "Allied alarm lost the threat location or did not reach its 70-block boundary")
            helper.assertTrue(!outsideAlly.isAlert(), "Allied alarm leaked beyond 70 blocks")
        }
        helper.runAtTickTime(25) {
            val data = GunData.from(shooter.mainHandItem)
            data.attachment.set(AttachmentType.BARREL, 2)
            data.save()
            val radius = Ports.guns.inHand(shooter)!!.hearingRadius
            val quietNear = actor(10.5 + radius * 0.8, SquadFaction.COW)
            val quietFar = actor(10.5 + radius * 1.2, SquadFaction.COW)
            Hearing.gunshot(helper.level, shooter, radius, suppressed = false)
            helper.assertTrue(quietNear.listening(), "Suppressed shot was entirely inaudible")
            helper.assertTrue(!quietFar.listening(), "Suppressor failed to limit the hearing radius")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 40, batch = "hearing")
    fun explosionIsCheckedByTwoOfASquadWhileTheRestTurn(helper: GameTestHelper) {
        for (x in 0..79) for (z in 0..47) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val squadMen = (0..4).map { AttackDeploymentGameTests.spawn(helper, Vec3(10.5 + it * 4, 1.0, 20.5), SquadFaction.PIG).apply { isNoAi = true } }
        val loner = AttackDeploymentGameTests.spawn(helper, Vec3(10.5, 1.0, 30.5), SquadFaction.PIG).apply { isNoAi = true }
        val thrower = AttackDeploymentGameTests.spawn(helper, Vec3(60.5, 1.0, 20.5), SquadFaction.COW).apply { isNoAi = true }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, squadMen.map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        cleanup(helper) { (squadMen + loner + thrower).forEach { it.discard() } }
        helper.runAtTickTime(1) {
            Hearing.explosion(helper.level, helper.absoluteVec(Vec3(40.5, 1.0, 20.5)), 4.0f, thrower)
            helper.assertTrue((squadMen + loner).all { it.listening() }, "Not everyone in earshot turned towards the explosion")
            // The two nearest the blast (x 26.5 and 22.5) go to look; the rest of the squad holds.
            helper.assertTrue(squadMen.filter { it.isAlert() } == squadMen.takeLast(2), "The squad did not send exactly its two nearest men to look")
            helper.assertTrue(loner.isAlert(), "A man without a squad no longer went to look")
            // A second grenade elsewhere: the same two go, not a new pair.
            Hearing.explosion(helper.level, helper.absoluteVec(Vec3(10.5, 1.0, 5.5)), 4.0f, thrower)
            helper.assertTrue(squadMen.filter { it.isAlert() } == squadMen.takeLast(2), "A second explosion sent more of the squad")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 110, batch = "hearing")
    fun hostileLandingBehindNpcTurnsHeadBeforeAcquiringTarget(helper: GameTestHelper) {
        for (x in 0..23) for (z in 0..11) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(16.5, 1.0, 6.5), SquadFaction.COW).apply { diggedIn = true }
        val oldEnemy = AttackDeploymentGameTests.spawn(helper, Vec3(22.5, 1.0, 6.5), SquadFaction.PIG).apply { isNoAi = true }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.COW, listOf(npc.uuid))
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setFocus(squad.id, oldEnemy.uuid)
        for (y in 1..3) helper.setBlock(BlockPos(19, y, 6), Blocks.STONE)
        val player = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "landing-test"))
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL)
        player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)!!.baseValue = 5000.0
        player.health = player.maxHealth
        player.setNoGravity(true)
        val behind = helper.absoluteVec(Vec3(13.75, 1.0, 6.5))
        player.moveTo(behind.x, behind.y, behind.z, 0.0f, 0.0f)
        helper.level.addNewPlayer(player)
        cleanup(helper) {
            manager.deleteSquad(helper.level, squad.id)
            listOf(npc, oldEnemy).forEach { it.discard() }
            helper.level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED)
        }
        helper.runAtTickTime(5) {
            npc.yRot = -90.0f
            npc.yHeadRot = -90.0f
            BrainUtils.setTargetOfEntity(npc, oldEnemy)
            player.gameEvent(GameEvent.HIT_GROUND)
            helper.assertTrue(!npc.listening() && npc.target === oldEnemy, "Factionless footsteps unexpectedly created a hostile stimulus")
        }
        helper.runAtTickTime(30) {
            PlayerFactionRegistry.get(helper.level).set(player.uuid, SquadFaction.PIG)
            npc.yRot = -90.0f
            npc.yHeadRot = -90.0f
            BrainUtils.setTargetOfEntity(npc, oldEnemy)
            helper.assertTrue(!com.sbwnpc.squad.combat.DetectionSightline.canSee(npc, oldEnemy), "Old target is not hidden in the landing regression")
            player.gameEvent(GameEvent.HIT_GROUND)
            helper.assertTrue(npc.listening(), "Vanilla hostile landing behind the NPC was not heard")
            helper.assertTrue(npc.target === oldEnemy, "Noise directly replaced the old target before visual discovery")
            helper.assertTrue(!npc.isAlert(), "Dug-in NPC abandoned its holding state to investigate a landing")
        }
        helper.runAtTickTime(90) {
            helper.assertTrue(npc.target === player, "NPC did not turn and visually discover the hostile behind it")
            helper.assertTrue(npc.position().distanceToSqr(helper.absoluteVec(Vec3(16.5, 1.0, 6.5))) < 0.01, "Hearing moved the NPC away from its held post")
            npc.hear(npc.position().add(0.0, 0.0, 2.0), nearbyFootstep = true)
            helper.assertTrue(!npc.checkingNearbyNoise() && npc.target === player, "Footsteps stole attention from a currently visible enemy")
            helper.succeed()
        }
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
