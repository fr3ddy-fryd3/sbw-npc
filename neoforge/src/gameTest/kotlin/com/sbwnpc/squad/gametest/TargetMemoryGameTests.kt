package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.FiringOpportunity
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.combat.tactics.TacticalPattern
import com.sbwnpc.squad.combat.tactics.TacticalSnapshotCollector
import com.sbwnpc.squad.combat.tactics.TacticalTargetSelection.Opportunity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.SquadTargetSensor
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.tslat.smartbrainlib.util.BrainUtils
import java.util.UUID

/** Real server entities/sensor and vanilla player respawn; no mocked target eligibility. */
@PrefixGameTestTemplate(false)
object TargetMemoryGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 60, batch = "target-life-memory")
    fun distantPlayerRespawnRequiresFreshDetectionWithoutErasingOtherEnemies(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "player-respawn-memory", recordTrace = false)
        val npc = scene.spawn(Vec3(20.5, 1.0, 45.5)).apply { isNoAi = true }
        val other = scene.enemy(Vec3(65.5, 1.0, 65.5))
        val crew = scene.spawn(Vec3(20.5, 1.0, 95.5)).apply { isNoAi = true }
        // Preserve a prior contact behind solid cover. It must not mask stale UUID rebinding
        // by offering a nearer shootable enemy during the reproduction.
        for (x in 54..56) for (z in 59..69) for (y in 1..3) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        val squad = scene.squad(listOf(npc), TacticalPattern.FOLLOW_ORDER, isolated = false, contact = other, order = SquadOrder.DEFEND)
        val profile = GameProfile(UUID.randomUUID(), "respawn-target")
        var player = ServerPlayer(helper.level.server, helper.level, profile, ClientInformation.createDefault()).apply {
            connection = FakePlayerFactory.get(helper.level, profile).connection
            gameMode.changeGameModeForPlayer(GameType.SURVIVAL)
            ServerPlayer::class.java.getDeclaredField("spawnInvulnerableTime").apply { isAccessible = true }.setInt(this, 0)
            setPos(helper.absoluteVec(Vec3(140.5, 1.0, 45.5)))
            helper.level.addNewPlayer(this)
            SquadTeams.assign(this, SquadFaction.COW)
        }
        // Vanilla respawn queries NeoForge channel attributes even with FakePlayer's no-op sender.
        // Supply a local channel for that query; no client or socket is involved.
        val connection = player.connection.connection
        val channelField = Connection::class.java.getDeclaredField("channel").apply { isAccessible = true }
        val previousChannel = channelField.get(connection)
        val channel = EmbeddedChannel()
        channelField.set(connection, channel)
        var respawned = false
        var deathClearedCurrentTarget = false
        scene.onClose = {
            TeamAwareness.forget(profile.id)
            SquadTeams.clear(player)
            if (respawned) {
                helper.level.server.playerList.remove(player)
            } else {
                helper.level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED)
            }
            channelField.set(connection, previousChannel)
            channel.finishAndReleaseAll()
        }
        helper.runAtTickTime(5) {
            SquadTactics.observe(npc, player) // A prior scout sighting, beyond this member's detection range.
            npc.rememberVisible(player)
            crew.rememberVehicleAttacker(player)
            BrainUtils.setTargetOfEntity(crew, other)
            BrainUtils.setTargetOfEntity(npc, player)
            squad.tactics.nextAssessment = helper.level.gameTime
            SquadTactics.refresh(npc)
            helper.assertTrue(npc.distanceTo(player) > 100 && DetectionSightline.canSee(npc, player), "Fixture must be distant and physically visible")
            helper.assertTrue(FiringOpportunity.assess(npc, player) == Opportunity.SHOOTABLE, "Distant living control is not physically shootable")
            helper.assertTrue(FiringOpportunity.assess(npc, other) == Opportunity.BLOCKED, "Secondary prior contact must actually be behind solid cover")
            helper.assertTrue(SquadTactics.preferredTarget(npc, helper.level) === player, "Known living distant target must remain engageable")
            val spawn = helper.absolutePos(BlockPos(150, 1, 45))
            player.setRespawnPosition(helper.level.dimension(), spawn, 0f, true, false)
            player.hurt(helper.level.damageSources().genericKill(), Float.MAX_VALUE)
            helper.assertTrue(!player.isAlive, "Fixture must trigger actual player death")
            deathClearedCurrentTarget = npc.target == null
            player = helper.level.server.playerList.respawn(player, false, Entity.RemovalReason.KILLED)
            respawned = true
            SquadTeams.assign(player, SquadFaction.COW)
            helper.assertTrue(player.uuid == profile.id && player.isAlive && npc.distanceTo(player) > 100, "Vanilla respawn must replace the life with the same UUID far away")
        }
        helper.runAtTickTime(6) {
            // This member is acquiring from a squad report, rather than holding the removed
            // entity object. Other members need not have personally targeted the old life.
            BrainUtils.setTargetOfEntity(npc, null)
            helper.assertTrue(helper.level.getEntity(profile.id) === player, "Respawned entity must be indexed by its persistent UUID")
            helper.assertTrue(FiringOpportunity.assess(npc, player) == Opportunity.SHOOTABLE, "Respawn control is not physically shootable")
            SquadTargetSensor().tick(helper.level, npc)
            helper.assertTrue(npc.target !== player, "Old contact reacquired the newly respawned player outside detection range")
            helper.assertTrue(deathClearedCurrentTarget, "Death event did not clear the existing combat target")
            helper.assertTrue(player.uuid !in squad.tactics.contacts, "Dead life remained in squad contacts")
            helper.assertTrue(player.uuid !in TeamAwareness.knownContacts(SquadFaction.PIG, helper.level.gameTime), "Dead life remained in faction awareness")
            helper.assertTrue(!npc.lastSeenFire.observed(player.uuid), "Personal sighting followed the UUID into a new life")
            helper.assertTrue(npc.rememberedEye(player) == null, "Remembered eye point followed the UUID into a new life")
            helper.assertTrue(crew.vehicleAttacker() == null && crew.target === other, "Crew retaliation rebound to the new life or erased an unrelated current target")
            helper.assertTrue(other.uuid in squad.tactics.contacts && npc.target === other, "Cleanup erased another enemy or disabled target sensing")
            player.setPos(helper.absoluteVec(Vec3(40.5, 1.0, 45.5)))
            npc.yHeadRot = -90f
            SquadTargetSensor().tick(helper.level, npc)
            helper.assertTrue(player.uuid in squad.tactics.contacts, "Respawned player cannot be detected again inside ordinary detection range")
            scene.result("PASS", "vanillaRespawn=true oldContactCleared=true otherEnemyPreserved=true freshDetection=true")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 180, batch = "target-reallocation")
    fun attackAndDefendReconsiderShootableDistantEnemyWithoutChangingTargetsEveryScan(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "target-reallocation", recordTrace = false)
        val setups = listOf(SquadOrder.ATTACK, SquadOrder.DEFEND).mapIndexed { index, order ->
            val z = 40.5 + index * 90
            val npc = scene.spawn(Vec3(20.5, 1.0, z)).apply { isNoAi = true }
            val far = scene.enemy(Vec3(140.5, 1.0, z))
            scene.squad(listOf(npc), TacticalPattern.FOLLOW_ORDER, isolated = false, contact = far, order = order)
            BrainUtils.setTargetOfEntity(npc, far)
            Triple(npc, far, SquadTargetSensor())
        }
        val nearby = mutableMapOf<UUID, NpcEntity>()
        helper.runAtTickTime(25) {
            setups.forEach { (npc, _, _) ->
                val at = helper.relativeVec(npc.position()).add(45.0, 0.0, 6.0)
                nearby[npc.uuid] = scene.enemy(at)
            }
        }
        val acquired = mutableMapOf<UUID, Long>()
        helper.onEachTick {
            setups.forEach { (npc, far, sensor) ->
                npc.yHeadRot = -90f
                sensor.tick(helper.level, npc)
                if (helper.tick < 25) helper.assertTrue(npc.target === far, "Known distant target was lost before another enemy appeared")
                val near = nearby[npc.uuid]
                if (near != null && npc.target === near) acquired.putIfAbsent(npc.uuid, helper.tick)
                if (npc.uuid in acquired) helper.assertTrue(npc.target === near, "Usable closer target was churned by a subsequent ordinary scan")
            }
            if (acquired.size == setups.size && helper.tick >= acquired.values.max() + 30) {
                scene.result("PASS", "acquired=$acquired stableForAtLeast30Ticks=true")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 150, batch = "target-coordinated-relay")
    fun blockedCoordinatedTargetYieldsToShootableAlliedReport(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "target-coordinated-relay", recordTrace = false)
        val npc = scene.spawn(Vec3(20.5, 1.0, 100.5)).apply { isNoAi = true }
        val blocked = scene.enemy(Vec3(100.5, 1.0, 100.5))
        val report = scene.enemy(Vec3(20.5, 1.0, 20.5))
        val spotter = scene.spawn(Vec3(32.5, 1.0, 40.5)).apply { isNoAi = true }
        val squad = scene.squad(listOf(npc), TacticalPattern.COORDINATED_ATTACK, contact = blocked)
        scene.squad(listOf(spotter), TacticalPattern.FOLLOW_ORDER, contact = report)
        for (y in 1..4) for (z in 90..110) helper.setBlock(BlockPos(50, y, z), Blocks.STONE)
        BrainUtils.setTargetOfEntity(npc, blocked)
        val sensor = SquadTargetSensor()
        helper.onEachTick {
            npc.yHeadRot = -90f
            helper.assertTrue(DetectionSightline.canSee(spotter, report), "Allied report has no actual spotter sightline")
            TeamAwareness.report(SquadFaction.PIG, report.uuid, report.position(), helper.level.gameTime, spotter.uuid.toString())
            squad.tactics.snapshot = TacticalSnapshotCollector.collect(squad, helper.level, listOf(npc), npc)
            val personallyKnown = report.uuid in squad.tactics.contacts
            if (helper.tick >= 70) {
                helper.assertTrue(FiringOpportunity.assess(npc, blocked) == Opportunity.BLOCKED, "Old target is not blocked")
                helper.assertTrue(FiringOpportunity.assess(npc, report) == Opportunity.SHOOTABLE, "Allied report is not shootable: ${FiringOpportunity.assess(npc, report)}")
                helper.assertTrue(squad.tactics.plan?.behavior is com.sbwnpc.squad.combat.tactics.CoordinatedAttackState, "Fixture lost coordinated state")
                helper.assertTrue(report.uuid in TeamAwareness.relayedContacts(SquadFaction.PIG, helper.level.gameTime, TeamAwareness.MEMORY_TICKS), "Allied report is not relayed")
            }
            sensor.tick(helper.level, npc)
            if (helper.tick < TeamAwareness.ALERT_DELAY_TICKS) helper.assertTrue(npc.target === blocked, "Relay delay was bypassed")
            if (npc.target === report) {
                helper.assertTrue(FiringOpportunity.assess(npc, report) == Opportunity.SHOOTABLE, "Replacement report has no safe shot")
                helper.assertTrue(!personallyKnown, "Fixture accidentally supplied a personal squad contact before target selection")
                scene.result("PASS", "acquired=${helper.tick} blockedContactPreserved=${blocked.uuid in squad.tactics.contacts}")
                helper.succeed()
            }
        }
    }
}
