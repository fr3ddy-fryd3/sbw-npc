package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.value.AttachmentType
import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.Alarm
import com.sbwnpc.squad.combat.Hearing
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.npc.NpcClass
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

    /**
     * Shells land near a defending man from a gun nobody sees (owner 08.11): he makes for the roofed
     * shed 12 blocks off and stands under it, rather than staying in the open.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 600, batch = "shelter")
    fun shelledManTakesTheShedWithoutSeeingTheMortar(helper: GameTestHelper) {
        for (x in 0..79) for (z in 0..47) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        // A 3x3 roof on four posts at x 32..34, z 19..21, three blocks up.
        for (x in 31..35) for (z in 18..22) helper.setBlock(BlockPos(x, 4, z), Blocks.OAK_PLANKS)
        for ((x, z) in listOf(31 to 18, 35 to 18, 31 to 22, 35 to 22)) for (y in 1..3) helper.setBlock(BlockPos(x, y, z), Blocks.OAK_LOG)
        val man = AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 20.5), SquadFaction.PIG)
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.DEFEND, helper.absolutePos(BlockPos(20, 1, 20)))
        cleanup(helper) {
            manager.deleteSquad(helper.level, squad.id)
            man.discard()
        }
        helper.onEachTick {
            if (helper.tick in 40L..400L && helper.tick % 40L == 0L) Hearing.explosion(helper.level, helper.absoluteVec(Vec3(14.5, 1.0, 14.5)), 4.0f, null)
            val at = helper.relativeVec(man.position())
            if (helper.tick > 40 && at.x in 31.0..36.0 && at.z in 18.0..23.0) helper.succeed()
            if (helper.tick == 590L) helper.fail("He stayed in the open at $at")
        }
    }

    /**
     * Four shelled men and a roof over one spot only (#321). The first takes it; the post searches
     * of the others under it fail, and the shelter method must then stand aside for each of them.
     * It took the same roof again every tick or two: in a playtest (12.11) 28 men stood in the open
     * failing one small roof for a minute. A second one-spot roof stands further off: the search
     * offers it to the next man rather than the one already taken.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 400, batch = "shelter")
    fun shelledMenGiveUpARoofTakenByAnother(helper: GameTestHelper) {
        for (x in 0..79) for (z in 0..47) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        helper.setBlock(BlockPos(33, 4, 20), Blocks.OAK_PLANKS)
        helper.setBlock(BlockPos(33, 4, 28), Blocks.OAK_PLANKS)
        val men = List(4) { AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 17.5 + it * 2), SquadFaction.PIG) }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.DEFEND, helper.absolutePos(BlockPos(20, 1, 20)))
        cleanup(helper) {
            manager.deleteSquad(helper.level, squad.id)
            men.forEach { it.discard() }
        }
        var most = 0
        helper.onEachTick {
            if (helper.tick in 40L..360L && helper.tick % 40L == 0L) Hearing.explosion(helper.level, helper.absoluteVec(Vec3(14.5, 1.0, 14.5)), 4.0f, null)
            most = maxOf(most, men.maxOf { com.sbwnpc.squad.ai.integration.NativeTactics.postFailures(it) })
            helper.assertTrue(most <= MAX_ROOF_FAILURES, "Post searches under a roof another holds failed $most times in a row")
            val sheltered = men.count { man -> listOf(20, 28).any { z -> man.position().distanceTo(helper.absoluteVec(Vec3(33.5, 1.0, z + 0.5))) < 1.0 } }
            if (helper.tick == 380L) helper.assertTrue(sheltered == 2, "$sheltered men under the two roofs")
            if (helper.tick == 380L) helper.succeed()
        }
    }

    /**
     * A machine gunner holds a 3-block tower with no enemy in sight, a shed stands on the ground 10
     * blocks off, and shells fall near (#321). He climbs down and takes the shed: his tower's post
     * floor followed him into the shelter search and refused every spot under it (playtest 12.11).
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 800, batch = "shelter")
    fun shelledSentryLeavesHisTowerForAShed(helper: GameTestHelper) {
        buildLookoutAndShed(helper)
        val man = AttackDeploymentGameTests.spawn(helper, Vec3(21.5, 4.0, 20.5), SquadFaction.PIG, role = NpcClass.MACHINE_GUNNER)
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.DEFEND, helper.absolutePos(BlockPos(21, 4, 20)))
        cleanup(helper) {
            manager.deleteSquad(helper.level, squad.id)
            man.discard()
        }
        helper.onEachTick {
            if (helper.tick in 120L..760L && helper.tick % 40L == 0L) Hearing.explosion(helper.level, helper.absoluteVec(Vec3(14.5, 1.0, 14.5)), 4.0f, null)
            val at = helper.relativeVec(man.position())
            // Up on his lookout until the first shell: he leaves it for the shelling, not for a post below.
            if (helper.tick == 119L) helper.assertTrue(at.y >= 3.0, "The sentry was off his lookout at $at before any shell")
            val underTheShed = at.y < 2.0 && at.x in 31.0..36.0 && at.z in 18.0..23.0
            if (helper.tick > 120 && underTheShed) helper.succeed()
            if (helper.tick == 790L) helper.fail("The sentry stayed at $at through the shelling")
        }
    }

    /** A lookout three blocks up behind a parapet with steps down its east side, and a shed ten blocks east. */
    private fun buildLookoutAndShed(helper: GameTestHelper) {
        val fill = { xs: IntRange, ys: IntRange, zs: IntRange, block: net.minecraft.world.level.block.Block ->
            for (x in xs) for (y in ys) for (z in zs) helper.setBlock(BlockPos(x, y, z), block)
        }
        fill(0..79, 0..0, 0..47, Blocks.STONE)
        fill(19..23, 1..4, 18..22, Blocks.STONE)
        // His floor inside the parapet, and its gap where the steps come up.
        fill(20..22, 4..4, 19..21, Blocks.AIR)
        fill(23..23, 4..4, 20..20, Blocks.AIR)
        fill(24..24, 1..2, 20..20, Blocks.STONE)
        fill(25..25, 1..1, 20..20, Blocks.STONE)
        // The arena's barrier ceiling stands within five blocks of the lookout and the shed top: a roof to the search.
        fill(10..40, 9..9, 10..30, Blocks.AIR)
        fill(31..35, 4..4, 18..22, Blocks.OAK_PLANKS)
        for ((x, z) in listOf(31 to 18, 35 to 18, 31 to 22, 35 to 22)) fill(x..x, 1..3, z..z, Blocks.OAK_LOG)
    }

    /** A failure or two while the roof is tried and its failure is remembered; one per tick was the loop. */
    private const val MAX_ROOF_FAILURES = 3

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 110, batch = "hearing")
    fun hostileLandingBehindNpcTurnsHeadBeforeAcquiringTarget(helper: GameTestHelper) {
        for (x in 0..23) for (z in 0..11) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(16.5, 1.0, 6.5), SquadFaction.COW).apply { claim(NpcActivity.DUG_IN, this@HearingGameTests) }
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

    /**
     * A noise near a defended point: the squad sends its two men nearest the spot to look, the rest
     * keep their posts, and the pair comes back once it has seen the spot (#254). A noise farther
     * than the squad's reach of its point sends nobody.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1400, batch = "hearing-squad")
    fun aDefendingSquadSendsItsTwoNearestToANoiseAndTakesThemBack(helper: GameTestHelper) {
        for (x in 0..159) for (z in 0..127) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val men = (0..3).map { AttackDeploymentGameTests.spawn(helper, Vec3(40.5 + it * 3, 1.0, 40.5), SquadFaction.PIG) }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(44, 1, 40)))
        cleanup(helper) { men.forEach { it.discard() } }
        fun scouts() = men.filter { NativeTactics.scouting(it) }
        val far = helper.absoluteVec(Vec3(44.5, 1.0, 40.5 + 80.0))
        val near = helper.absoluteVec(Vec3(44.5 + 36.0, 1.0, 40.5))
        helper.runAtTickTime(100) { men.first().hear(far) }
        helper.runAtTickTime(200) {
            helper.assertTrue(scouts().isEmpty(), "A noise 80 blocks from the defended point sent ${scouts().size} men")
            men.first().hear(near)
        }
        var sent: List<NpcEntity>? = null
        var closest = Double.MAX_VALUE
        helper.onEachTick {
            if (helper.tick < 220) return@onEachTick
            val now = scouts()
            if (sent == null && now.isNotEmpty()) {
                helper.assertTrue(now.size == 2, "The squad sent ${now.size} men, not a pair")
                // Their posts round the point have moved them since they spawned: nearest as they stand now.
                val nearest = men.sortedBy { it.position().distanceTo(near) }.take(2).toSet()
                helper.assertTrue(now.toSet() == nearest, "The pair sent was not the two nearest the noise")
                sent = now
            }
            sent?.let { pair -> closest = minOf(closest, pair.minOf { it.position().distanceTo(near) }) }
            if (sent != null && now.isEmpty()) {
                // Seen from 16 blocks at most, round a spot kept on an 8-block grid.
                helper.assertTrue(closest <= 22.0, "The pair turned back ${closest.toInt()} blocks short of the noise")
                helper.assertTrue((men - sent!!.toSet()).all { it.position().distanceTo(helper.absoluteVec(Vec3(44.5, 1.0, 40.5))) <= 24.0 }, "The men not sent left the defended point")
                helper.succeed()
            }
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
