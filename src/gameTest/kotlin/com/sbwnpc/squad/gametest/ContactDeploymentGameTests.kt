package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.tactics.DefensiveOverwatch
import com.sbwnpc.squad.combat.tactics.TacticalChoice
import com.sbwnpc.squad.combat.tactics.TacticalCoordinator
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalPattern
import com.sbwnpc.squad.combat.tactics.TacticalPhase
import com.sbwnpc.squad.combat.tactics.TacticalRuntimeEvents
import com.sbwnpc.squad.combat.tactics.TacticalSnapshotCollector
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
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

/** March scenes use natural selection; the crowded-cover scene isolates REORIENT execution. */
@PrefixGameTestTemplate(false)
object ContactDeploymentGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1000, batch = "contact-flat")
    fun firstContactUsesNearbyCoverWithoutUnprotectedRetreat(helper: GameTestHelper) = runScene(helper, uneven = false, rearCover = false)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1000, batch = "contact-terrain")
    fun firstContactUsesCoverAcrossUnevenMarch(helper: GameTestHelper) = runScene(helper, uneven = true, rearCover = false)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1000, batch = "contact-rear-cover")
    fun firstContactMayMoveBackToRealCover(helper: GameTestHelper) = runScene(helper, uneven = false, rearCover = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1000, batch = "contact-covered-crowd")
    fun shelteredShootersStillSeparateTheirPosts(helper: GameTestHelper) = runScene(helper, uneven = false, rearCover = false, crowdedCover = true)

    private fun runScene(helper: GameTestHelper, uneven: Boolean, rearCover: Boolean, crowdedCover: Boolean = false) {
        val name = when {
            crowdedCover -> "contact-covered-crowd"
            rearCover -> "contact-rear-cover"
            uneven -> "contact-terrain"
            else -> "contact-flat"
        }
        fun ground(x: Int, z: Int) = if (uneven && x in 46..54 && z in 70..130) 1 else 0
        for (x in 0..175) {
            for (z in 0..223) {
                for (y in 0..ground(x, z)) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
            }
        }
        // A fresh namespace avoids UUIDs retained by an earlier server world. Stable low bits
        // keep relative role/target ordering, with the same per-actor random seeds as the control.
        val prefix = UUID.randomUUID().mostSignificantBits
        val members = (0..7).map { index ->
            val x = if (!crowdedCover && index % 2 == 0) 58 else 38
            val z = if (crowdedCover) 70 + index * 3 else 78 + index * 6
            AttackDeploymentGameTests.spawn(helper, Vec3(x + 0.5, ground(x, z) + 1.0, z + 0.5), SquadFaction.PIG, UUID(prefix, index.toLong()), index.toLong())
        }
        val enemies = ArrayList<NpcEntity>()
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, members.map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(160, 1, 99)))
        val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "contact-observer"))
        witness.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
        val at = helper.absoluteVec(Vec3(90.5, 10.0, 50.5))
        witness.moveTo(at.x, at.y, at.z, 0.0f, 0.0f)
        witness.setNoGravity(true)
        helper.level.addNewPlayer(witness)
        val directory = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(directory)
        val csv = directory.resolve("$name.csv")
        Files.writeString(csv, "tick,npc,x,y,z,pattern,phase,job,postX,postZ,bodyCovered,shotClear,backFromContact,lastShot\n")
        Files.writeString(directory.resolve("$name-covers.csv"), "npc,x,y,z,wallX,wallZ\n")
        val contactPositions = HashMap<UUID, Vec3>()
        val previousPositions = HashMap<UUID, Vec3>()
        val exposedRetreaters = HashSet<UUID>()
        val coveredShooters = HashSet<UUID>()
        val protectedPosts = HashSet<UUID>()
        val projectileShooters = HashSet<UUID>()
        val rearCoverUsers = HashSet<UUID>()
        var contactTick: Long? = null
        var executedAt: Long? = null
        var completed = false
        var crowdingSince: Long? = null
        var longestCrowding = 0L
        val pacer = CombatTickPacer()
        fun protected(member: NpcEntity, point: Vec3): Boolean =
            (member.target ?: enemies.firstOrNull())?.let { enemy ->
                DefensiveOverwatch.nearbyCover(point, enemy.eyePosition) { from, to -> Sightline.blocked(helper.level, from, to, member) }
            } == true
        fun shotClear(member: NpcEntity, point: Vec3): Boolean =
            enemies.any { enemy ->
                !Sightline.blocked(helper.level, point.add(0.0, member.eyeHeight.toDouble(), 0.0), enemy.eyePosition, member)
            }
        val rounds = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile
            val member = members.firstOrNull { it.uuid == round?.owner?.uuid }
            if (event.level === helper.level && round != null && member != null && Ports.guns.isRound(round)) {
                projectileShooters += member.uuid
                if (protected(member, member.position())) coveredShooters += member.uuid
            }
        }
        DebugFlags.enabledGroups = setOf(LogGroup.ORDER, LogGroup.PATH, LogGroup.STUCK)
        NeoForge.EVENT_BUS.addListener(rounds)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        var cleaned = false
        fun summary(result: String) =
            Files.writeString(
                directory.resolve("$name-summary.txt"),
                "result=$result contact=$contactTick executing=$executedAt completed=$completed exposedRetreaters=$exposedRetreaters protectedPosts=$protectedPosts coveredShooters=$coveredShooters projectileShooters=$projectileShooters rearCoverUsers=$rearCoverUsers longestCrowding=$longestCrowding\n",
            )
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
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) {
                summary("FAIL")
                cleanup()
            }
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
        })
        helper.runAtTickTime(40) {
            if (crowdedCover) return@runAtTickTime
            helper.assertTrue(squad.tactics.plan?.pattern == TacticalPattern.FOLLOW_ORDER, "Fixture did not start with a contact-free march")
            val farthest = members.maxOf { helper.relativeVec(it.position()).x }
            for ((index, member) in members.withIndex()) {
                val point = helper.relativeVec(member.position())
                val x = kotlin.math.floor(point.x).toInt() + if (rearCover) -3 else 3
                val z = kotlin.math.floor(point.z).toInt()
                // Rear actors may legitimately share a forward actor's shelter. Give the
                // leading group enough protected width for two posts at the normal interval.
                val width = if (rearCover && index % 2 == 0) 3 else 1
                for (offset in -width..width) helper.setBlock(BlockPos(x, ground(x, z + offset) + 1, z + offset), Blocks.STONE)
                Files.writeString(directory.resolve("$name-covers.csv"), "${member.uuid},${point.x},${point.y},${point.z},$x,$z\n", APPEND)
            }
            for (index in 0..2) {
                enemies += AttackDeploymentGameTests.spawn(helper, Vec3(farthest + 60.0, 1.0, 93.5 + index * 6), SquadFaction.COW, UUID(prefix, 100L + index), 100L + index).apply {
                    isNoAi = true
                    getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
                    health = maxHealth
                }
            }
        }
        if (crowdedCover) {
            for (z in 58..125) helper.setBlock(BlockPos(40, 1, z), Blocks.STONE)
            for (index in 0..2) {
                enemies += AttackDeploymentGameTests.spawn(helper, Vec3(100.5, 1.0, 78.5 + index * 6), SquadFaction.COW, UUID(prefix, 100L + index), 100L + index).apply {
                    isNoAi = true
                    getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
                    health = maxHealth
                }
            }
            // Isolate retaining existing sheltered posts. Detection, movement, readiness and
            // projectiles stay real; a flank must not conceal a crowd by sending it away.
            TacticalRuntimeEvents.bind(squad, helper.level)
            squad.tactics.select(TacticalChoice(TacticalPattern.REORIENT, enemies[1].position()), squad.orderStamp, helper.level.gameTime)
            squad.tactics.nextAssessment = Long.MAX_VALUE
        }
        helper.onEachTick {
            if (crowdedCover && helper.tick % 10L == 0L) {
                val view = TacticalSnapshotCollector.collect(squad, helper.level, members, members.first())
                squad.tactics.snapshot = view
                squad.tactics.plan?.let { TacticalCoordinator.advance(squad.id, squad.tactics, it, view) }
            }
            val plan = squad.tactics.plan
            if (plan != null && plan.pattern != TacticalPattern.FOLLOW_ORDER && contactTick == null) {
                contactTick = helper.tick
                members.forEach { contactPositions[it.uuid] = it.position() }
            }
            if (contactTick == null) return@onEachTick
            if (plan?.phase == TacticalPhase.EXECUTING && executedAt == null) executedAt = helper.tick
            if (plan?.phase == TacticalPhase.COMPLETED) completed = true
            if (crowdedCover && helper.tick >= 200) {
                val crowded = members.any { left -> members.any { right -> left !== right && left.position().distanceTo(right.position()) < FiringSpots.MIN_SPACING - 0.05 } }
                if (crowded) {
                    if (crowdingSince == null) crowdingSince = helper.tick
                    longestCrowding = maxOf(longestCrowding, helper.tick - crowdingSince!!)
                } else {
                    crowdingSince = null
                }
            }
            for (member in members) {
                val task = plan?.tasks?.get(member.uuid)
                val point = member.position()
                val post = task?.position
                val bodyCovered = protected(member, point)
                val forward = task?.frontage?.forward ?: Vec3(1.0, 0.0, 0.0)
                val back = contactPositions.getValue(member.uuid).subtract(point).dot(forward)
                val retreating = previousPositions.put(member.uuid, point)?.let { point.subtract(it).dot(forward) < -0.01 } == true
                val activePath = member.navigation.path?.takeUnless { it.isDone }
                val walkingPost = post ?: activePath?.endNode?.let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
                val postCovered = walkingPost != null && protected(member, walkingPost)
                // A lateral route can oscillate in X after an earlier justified retreat.
                // Measure an actual backward destination along the owned front as well.
                val backwardRoute = walkingPost?.subtract(point)?.dot(forward)?.let { it < -2.0 } == true
                val preparing = plan?.phase in setOf(TacticalPhase.PREPARING, TacticalPhase.OPENING_LANE)
                if (preparing && activePath != null && retreating && backwardRoute && task?.job in TacticalJob.FIRE_SUPPORT && back > 3.0 && !bodyCovered && !postCovered && exposedRetreaters.add(member.uuid)) {
                    Files.writeString(
                        directory.resolve("$name-retreats.txt"),
                        "tick=${helper.tick} npc=${member.uuid} plan=${plan?.id} from=${helper.relativeVec(point)} post=${post?.let(helper::relativeVec)} walkingPost=${walkingPost?.let(helper::relativeVec)} target=${member.target?.position()?.let(helper::relativeVec)}\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        APPEND,
                    )
                }
                if (bodyCovered && post != null && protected(member, post) && post.distanceTo(point) < 1.5 && shotClear(member, point)) {
                    protectedPosts += member.uuid
                    if (back > 1.0) rearCoverUsers += member.uuid
                }
                if (helper.tick % 10L == 0L) {
                    val local = helper.relativeVec(point)
                    val localPost = post?.let(helper::relativeVec)
                    Files.writeString(csv, "${helper.tick},${member.uuid},${local.x},${local.y},${local.z},${plan?.pattern},${plan?.phase},${task?.job},${localPost?.x},${localPost?.z},$bodyCovered,${shotClear(member, point)},$back,${member.lastShotTick}\n", APPEND)
                }
            }
        }
        helper.runAtTickTime(999) {
            summary("CHECK")
            helper.assertTrue(contactTick != null && executedAt != null, "Natural first contact did not launch a maneuver; see $name-summary.txt")
            helper.assertTrue(exposedRetreaters.isEmpty(), "Contact caused unprotected backward movement: $exposedRetreaters")
            if (rearCover) {
                // The rear group's shots intersect the front group; require every leading
                // actor to use its rear shelter without asking the others to shoot through allies.
                val leading = members.filterIndexed { index, _ -> index % 2 == 0 }.map { it.uuid }.toSet()
                helper.assertTrue(protectedPosts.containsAll(leading), "Not every leading NPC occupied its rear shelter")
                helper.assertTrue(coveredShooters.containsAll(leading), "Not every leading NPC fired from its rear shelter")
                helper.assertTrue(rearCoverUsers.containsAll(leading), "A leading NPC did not move back to real cover")
            } else {
                helper.assertTrue(protectedPosts.size >= 6, "Fewer than 6/8 stood in physically sheltered posts with a shot: ${protectedPosts.size}")
                helper.assertTrue(coveredShooters.size >= 6, "Fewer than 6/8 fired real rounds from cover: ${coveredShooters.size}")
            }
            helper.assertTrue(projectileShooters.size == 8, "Some NPCs never fired real projectiles: ${projectileShooters.size}/8")
            if (crowdedCover) {
                helper.assertTrue(
                    squad.tactics.plan?.pattern == TacticalPattern.REORIENT && squad.tactics.plan!!.tasks.size == 8,
                    "Crowd escaped the test by losing its coordinated assignments",
                )
                helper.assertTrue(longestCrowding < 40, "Covered shooters remained crowded after settling: $longestCrowding ticks")
            }
            summary("PASS")
            helper.succeed()
        }
    }
}
