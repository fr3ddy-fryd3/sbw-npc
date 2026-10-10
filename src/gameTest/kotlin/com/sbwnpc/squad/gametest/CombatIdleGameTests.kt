package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringPostArrival
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.WeaponReach
import com.sbwnpc.squad.combat.tactics.*
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadDeployment
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
import java.nio.file.StandardOpenOption.CREATE
import java.util.UUID
import java.util.function.Consumer

/** Physical regressions for a remote reserve and a firing post whose local disk has no shot. */
@PrefixGameTestTemplate(false)
object CombatIdleGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 700, batch = "blind-local-bounds")
    fun blindUnassignedAttackerKeepsApproachingInsteadOfTimedPauses(helper: GameTestHelper) {
        val scene = Scene(helper, "blind-local-bounds")
        for (x in 40..100) {
            for (z in 0..223) {
                val height = minOf(8, (x - 40) / 2 + 1, (100 - x) / 2 + 1)
                for (y in 1..height) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
            }
        }
        val shooter = scene.spawn(Vec3(30.5, 1.0, 100.5))
        val enemy = scene.enemy(Vec3(150.5, 1.0, 100.5))
        val squad = scene.squad(listOf(shooter), TacticalPattern.FOLLOW_ORDER)
        var idle = 0
        var longest = 0
        helper.onEachTick {
            scene.tick(squad)
            val blind = Sightline.blocked(helper.level, shooter.eyePosition, enemy.eyePosition, shooter)
            idle = if (shooter.target != null && blind && shooter.onGround() && shooter.navigation.isDone && shooter.deltaMovement.horizontalDistanceSqr() < 0.0025) idle + 1 else 0
            longest = maxOf(longest, idle)
            helper.assertTrue(idle < 45, "Blind attacker paused instead of advancing for $idle ticks")
            if (shooter.lastShotTick >= 0) {
                scene.result("PASS", "fired=${helper.tick} longestBlindPause=$longest at=${helper.relativeVec(shooter.position())}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 40, batch = "infantry-presets")
    fun infantryPresetsDeployShotgunnersWithoutLosingSupport(helper: GameTestHelper) {
        val scene = Scene(helper, "infantry-presets")
        val expected = listOf(
            SquadPreset.FIVE to listOf(3, 1, 0, 0, 1),
            SquadPreset.SEVEN to listOf(3, 1, 1, 1, 1),
            SquadPreset.SIXTEEN to listOf(9, 1, 2, 2, 2),
            SquadPreset.THIRTY_TWO to listOf(18, 2, 4, 4, 4),
        )
        val roles = listOf(NpcClass.RIFLEMAN, NpcClass.SHOTGUNNER, NpcClass.SNIPER, NpcClass.MACHINE_GUNNER, NpcClass.MEDIC)
        for ((index, entry) in expected.withIndex()) {
            val members = scene.deploy(entry.first, Vec3(30.5 + index * 35, 1.0, 100.5))
            helper.assertTrue(roles.map { role -> members.count { it.npcClass == role } } == entry.second, "Deployment changed the support/shotgun roster of ${entry.first}")
            helper.assertTrue(members.size == entry.second.sum() && members.all { it.npcRank == NpcRank.RECRUIT }, "Preset lost strength or spawned veterans")
            helper.assertTrue(members.filter { it.npcClass == NpcClass.SHOTGUNNER }.all { Ports.guns.roundsLeft(it.primaryGun()) == 64 && !it.sidearmWeapon.isEmpty }, "Deployed shotgunner has no primary ammo or backup pistol")
        }
        scene.result("PASS", "sizes=5,7,16,32 shotgunners=1,1,1,2")
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1000, batch = "mixed-infantry-contact")
    fun mixedInfantryShotgunnerJoinsWeaponRangeAndFires(helper: GameTestHelper) {
        val scene = Scene(helper, "mixed-infantry-contact")
        val members = scene.deploy(SquadPreset.SEVEN, Vec3(20.5, 1.0, 100.5))
        val enemy = scene.enemy(Vec3(140.5, 1.0, 100.5))
        val squad = scene.squad(members, TacticalPattern.REORIENT, isolated = false)
        val shooter = members.single { it.npcClass == NpcClass.SHOTGUNNER }
        val fired = mutableSetOf<UUID>()
        var firstShotgunDistance: Float? = null
        val listener = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile ?: return@Consumer
            if (event.level !== helper.level || !Ports.guns.isRound(round) || round.owner !in members) return@Consumer
            fired += round.owner!!.uuid
            if (round.owner === shooter) {
                helper.assertTrue(WeaponReach.inRange(shooter, shooter.eyePosition, enemy.eyePosition), "Mixed squad fired M870 beyond effective range")
                if (firstShotgunDistance == null) firstShotgunDistance = shooter.distanceTo(enemy)
            }
        }
        NeoForge.EVENT_BUS.addListener(listener)
        scene.onClose = { NeoForge.EVENT_BUS.unregister(listener) }
        helper.onEachTick {
            scene.tick(squad)
            val fighters = members.filter { it.npcClass != NpcClass.MEDIC }
            if (fighters.all { it.uuid in fired }) {
                scene.result("PASS", "tick=${helper.tick} combatShooters=${fighters.size} totalShooters=${fired.size} firstShotgunDistance=$firstShotgunDistance")
                Files.writeString(
                    Path.of(System.getProperty("sbwnpc.gameTestReports")).resolve("mixed-infantry-contact-runs.txt"),
                    "tick=${helper.tick} combatShooters=${fighters.size} totalShooters=${fired.size} firstShotgunDistance=$firstShotgunDistance\n",
                    CREATE,
                    APPEND,
                )
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 900, batch = "remote-reserve")
    fun freshMedicJoinsRemoteReserveDuringContact(helper: GameTestHelper) {
        val scene = Scene(helper, "remote-reserve")
        val lead = (0..3).map { scene.spawn(Vec3(110.5, 1.0, 90.5 + it * 6)) }
        val medic = scene.spawn(Vec3(12.5, 1.0, 100.5), NpcClass.MEDIC)
        scene.enemy(Vec3(170.5, 1.0, 100.5))
        val squad = scene.squad(lead + medic, TacticalPattern.REORIENT)
        val start = medic.position()
        helper.onEachTick {
            scene.tick(squad)
            if (helper.tick > 40) helper.assertTrue(squad.tactics.plan!!.tasks[medic.uuid]?.job == TacticalJob.RESERVE, "Medic lost its reserve assignment")
            if (medic.x - start.x >= 85.0) {
                scene.result("PASS", "joined=${helper.tick} distance=${medic.position().distanceTo(start)}")
                helper.succeed()
            }
        }
        helper.runAtTickTime(850) {
            scene.result("FAIL", "position=${helper.relativeVec(medic.position())} task=${squad.tactics.plan!!.tasks[medic.uuid]?.position}")
            helper.fail("Fresh medic never joined its remote reserve; see remote-reserve.csv")
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "closed-wide-post")
    fun coveredAttackerCrossesWideBlindRidgeAndResumesFire(helper: GameTestHelper) {
        val scene = Scene(helper, "closed-wide-post")
        val shooter = scene.spawn(Vec3(30.5, 1.0, 100.5))
        val enemy = scene.enemy(Vec3(110.5, 1.0, 100.5))
        val rear = scene.spawn(Vec3(30.5, 1.0, 80.5))
        val scout = scene.spawn(Vec3(130.5, 1.0, 140.5))
        val squad = scene.squad(listOf(shooter, rear, scout), TacticalPattern.REORIENT)
        var closed = false
        var lastOpenShot = -1
        helper.onEachTick {
            scene.tick(squad)
            if (!closed && shooter.lastShotTick >= 0) {
                lastOpenShot = shooter.lastShotTick
                val destination = helper.absoluteVec(Vec3(150.5, 1.0, 100.5))
                enemy.setPos(destination)
                for (x in 40..100) {
                    for (z in 0..223) {
                        val height = minOf(8, (x - 40) / 2 + 1, (100 - x) / 2 + 1)
                        for (y in 1..height) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
                    }
                }
                helper.assertTrue(Sightline.blocked(helper.level, shooter.eyePosition, enemy.eyePosition, shooter), "Ridge did not close the established firing post")
                helper.assertTrue(helper.relativeVec(shooter.position()).x < 38.0, "Fixture closed the ridge on top of the moving shooter")
                closed = true
            }
            if (closed && shooter.lastShotTick > lastOpenShot && helper.relativeVec(shooter.position()).x > 56.0) {
                scene.result("PASS", "resumed=${helper.tick} at=${helper.relativeVec(shooter.position())}")
                helper.assertTrue(shooter.position().subtract(enemy.position()).horizontalDistance() >= 48.0, "Recovery charged into the enemy")
                val post = squad.tactics.plan!!.tasks[shooter.uuid]?.position
                if (post != null) helper.assertTrue(kotlin.math.abs(helper.relativeVec(post).z - 100.5) <= 8.0, "Recovery assigned a post outside the owned lateral lane")
                // The chosen block center may lie on the boundary. Physical arrival already
                // permits 0.4 blocks; a 0.01-block settling overshoot is not another lane.
                helper.assertTrue(
                    kotlin.math.abs(helper.relativeVec(shooter.position()).z - 100.5) <= 8.0 ||
                        (post != null && FiringPostArrival.reached(shooter.position(), post)),
                    "Recovery abandoned the owned lateral lane",
                )
                helper.succeed()
            }
        }
        helper.runAtTickTime(1150) {
            scene.result("FAIL", "closed=$closed at=${helper.relativeVec(shooter.position())} lastShot=${shooter.lastShotTick} task=${squad.tactics.plan!!.tasks[shooter.uuid]?.job}")
            helper.fail("Attacker stayed behind a wide ridge without resuming fire; see closed-wide-post.csv")
        }
    }

    internal class Scene(val helper: GameTestHelper, val name: String) {
        private val entities = mutableListOf<NpcEntity>()
        private val squads = mutableListOf<Squad>()
        private val oldGroups = DebugFlags.enabledGroups
        private val manager = SquadManager.get(helper.level)
        private val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        private val pacer = CombatTickPacer()
        private val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "idle-observer"))
        private var cleaned = false
        var onClose: () -> Unit = {}
        init {
            for (x in 0..175) for (z in 0..223) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
            Files.createDirectories(report)
            Files.writeString(report.resolve("$name.csv"), "tick,npc,role,x,y,z,job,pathDone,shot,search,phase\n")
            DebugFlags.enabledGroups = oldGroups + setOf(LogGroup.ORDER, LogGroup.PATH)
            witness.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            val at = helper.absoluteVec(Vec3(90.5, 20.0, 100.5))
            witness.moveTo(at.x, at.y, at.z, 0f, 0f)
            witness.setNoGravity(true)
            helper.level.addNewPlayer(witness)
            NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
            helper.testInfo.addListener(object : GameTestListener {
                override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
                override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
                override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
                override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            })
        }
        fun spawn(at: Vec3, role: NpcClass = NpcClass.RIFLEMAN): NpcEntity = AttackDeploymentGameTests.spawn(helper, at, SquadFaction.PIG, role = role).also { entities += it }
        fun deploy(preset: SquadPreset, at: Vec3): List<NpcEntity> {
            val config = SquadToolItem.Config(NpcClass.RIFLEMAN, NpcRank.RECRUIT, SquadFaction.PIG, preset)
            val result = SquadDeployment.deploy(helper.level, BlockPos.containing(helper.absoluteVec(at)), 0f, config, UUID.randomUUID())!!
            entities += result.members
            result.squad?.let { manager.disband(helper.level, it.id) }
            result.members.forEach {
                it.rgnLeft = 0
                it.rgoLeft = 0
            }
            return result.members
        }
        fun enemy(at: Vec3): NpcEntity =
            AttackDeploymentGameTests.spawn(helper, at, SquadFaction.COW).apply {
                isNoAi = true
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 50000.0
                health = maxHealth
                entities += this
            }
        fun squad(members: List<NpcEntity>, pattern: TacticalPattern, isolated: Boolean = true): Squad =
            manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, members.map { it.uuid }).also {
                squads += it
                manager.setOrder(it.id, SquadOrder.ATTACK)
                val enemy = entities.last { npc -> npc.spawnFaction == SquadFaction.COW }
                manager.setObjective(helper.level, it.id, enemy.blockPosition())
                TacticalRuntimeEvents.bind(it, helper.level)
                // Start with a reported contact. Detection/target selection and the actual gun still
                // run normally; this fixture isolates post recovery rather than scout placement.
                val observer = members.firstOrNull { npc -> npc.npcClass == NpcClass.SHOTGUNNER } ?: members.first()
                SquadTactics.observe(observer, enemy)
                helper.assertTrue(enemy.uuid in it.tactics.contacts, "Observer class failed to report its contact")
                if (isolated) {
                    it.tactics.select(TacticalChoice(pattern, enemy.position()), it.orderStamp, helper.level.gameTime)
                    it.tactics.nextAssessment = Long.MAX_VALUE
                } else {
                    SquadTactics.refresh(observer)
                    helper.assertTrue(it.tactics.snapshot!!.members.any { member -> member.id == observer.uuid }, "Production assessment excluded the shotgunner")
                }
            }
        fun tick(squad: Squad) {
            val members = entities.filter { it.squadId == squad.id }
            helper.assertTrue(squad.tactics.plan != null, "Fixture lost its tactical plan: order=${squad.order} alive=${members.count { it.isAlive }}")
            if (helper.tick % 10L == 0L) {
                if (squad.tactics.nextAssessment == Long.MAX_VALUE) {
                    val view = TacticalSnapshotCollector.collect(squad, helper.level, members, members.first())
                    squad.tactics.snapshot = view
                    TacticalCoordinator.advance(squad.id, squad.tactics, squad.tactics.plan!!, view)
                } else {
                    SquadTactics.refresh(members.first())
                }
                for (npc in members) {
                    val task = squad.tactics.plan!!.tasks[npc.uuid]
                    val p = helper.relativeVec(npc.position())
                    Files.writeString(report.resolve("$name.csv"), "${helper.tick},${npc.uuid},${npc.npcClass},${p.x},${p.y},${p.z},${task?.job},${npc.navigation.isDone},${npc.lastShotTick},${task?.search?.probeIndex},${squad.tactics.plan!!.phase}\n", APPEND)
                }
            }
        }
        fun result(outcome: String, details: String) = Files.writeString(report.resolve("$name.txt"), "result=$outcome $details\n")
        private fun cleanup() {
            if (cleaned) return
            cleaned = true
            DebugFlags.enabledGroups = oldGroups
            onClose()
            NeoForge.EVENT_BUS.unregister(pacer)
            squads.forEach { manager.deleteSquad(helper.level, it.id) }
            entities.forEach {
                FiringSpots.release(it.uuid)
                it.discard()
            }
            helper.level.removePlayerImmediately(witness, Entity.RemovalReason.DISCARDED)
        }
    }
}
