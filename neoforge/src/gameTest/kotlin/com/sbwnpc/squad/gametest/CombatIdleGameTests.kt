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
        val squad = scene.squad(listOf(shooter))
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
        val squad = scene.squad(members)
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
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "closed-wide-post")
    fun coveredAttackerCrossesWideBlindRidgeAndResumesFire(helper: GameTestHelper) {
        val scene = Scene(helper, "closed-wide-post")
        val shooter = scene.spawn(Vec3(30.5, 1.0, 100.5))
        val enemy = scene.enemy(Vec3(110.5, 1.0, 100.5))
        val rear = scene.spawn(Vec3(30.5, 1.0, 80.5))
        val scout = scene.spawn(Vec3(130.5, 1.0, 140.5))
        val squad = scene.squad(listOf(shooter, rear, scout))
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
                val post = com.sbwnpc.squad.ai.integration.NativeTactics.assignment(shooter)?.position
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
            scene.result("FAIL", "closed=$closed at=${helper.relativeVec(shooter.position())} lastShot=${shooter.lastShotTick} task=${com.sbwnpc.squad.ai.integration.NativeTactics.assignment(shooter)?.job}")
            helper.fail("Attacker stayed behind a wide ridge without resuming fire; see closed-wide-post.csv")
        }
    }

    internal class Scene(val helper: GameTestHelper, val name: String, depth: Int = 224, private val recordTrace: Boolean = true) {
        private val entities = mutableListOf<NpcEntity>()
        private val squads = mutableListOf<Squad>()
        private val oldGroups = DebugFlags.enabledGroups
        private var manager = SquadManager.get(helper.level)
        private val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        private val pacer = CombatTickPacer()
        private val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "idle-observer"))
        private var cleaned = false
        var onClose: () -> Unit = {}
        init {
            for (x in 0..175) for (z in 0 until depth) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
            Files.createDirectories(report)
            Files.writeString(report.resolve("$name.csv"), "tick,npc,role,x,y,z,job,pathDone,shot,search,phase\n")
            DebugFlags.enabledGroups = if (recordTrace) oldGroups + setOf(LogGroup.ORDER, LogGroup.PATH) else emptySet()
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
        fun spawn(at: Vec3, role: NpcClass = NpcClass.RIFLEMAN, faction: SquadFaction = SquadFaction.PIG): NpcEntity = AttackDeploymentGameTests.spawn(helper, at, faction, role = role).also { entities += it }
        fun deploy(preset: SquadPreset, at: Vec3, config: SquadToolItem.Config = SquadToolItem.Config(NpcClass.RIFLEMAN, NpcRank.RECRUIT, SquadFaction.PIG, preset)): List<NpcEntity> {
            val result = SquadDeployment.deploy(helper.level, BlockPos.containing(helper.absoluteVec(at)), 0f, config, UUID.randomUUID())!!
            entities += result.members
            result.squad?.let { manager.disband(helper.level, it.id) }
            result.members.forEach {
                it.rgnLeft = 0
                it.rgoLeft = 0
            }
            return result.members
        }
        fun deployFixed(preset: SquadPreset, at: Vec3, group: Int): List<NpcEntity> =
            preset.composition.mapIndexed { index, role ->
                val identity = UUID.nameUUIDFromBytes("$name:$group:$index".toByteArray())
                val offset = com.sbwnpc.squad.combat.SquadFormation.gridOffset(index, preset.composition.size)
                AttackDeploymentGameTests.spawn(helper, at.add(offset), SquadFaction.PIG, identity, 79L + group * 32 + index, role).also {
                    it.yRot = 180f
                    it.yHeadRot = 180f
                    entities += it
                }
            }

        /** Stable operation membership as well as stable actors, equipment and initial grid. */
        fun stabilizeSquads(current: List<Squad>): List<Squad> {
            val identities = current.mapIndexed { index, squad -> squad.id to UUID.nameUUIDFromBytes("$name:squad:$index".toByteArray()) }.toMap()
            val saved = manager.saveState(net.minecraft.nbt.CompoundTag())
            for (entry in saved.getList("Squads", net.minecraft.nbt.Tag.TAG_COMPOUND.toInt())) {
                val tag = entry as net.minecraft.nbt.CompoundTag
                identities[tag.getUUID("Id")]?.let { tag.putUUID("Id", it) }
            }
            manager = SquadManager.load(saved)
            helper.level.dataStorage.set("sbwnpc_squads", manager)
            SquadManager.clearCache()
            entities.forEach { npc -> identities[npc.squadId]?.let { npc.squadId = it } }
            val replacements = current.map { old ->
                manager.all().single { it.id == identities.getValue(old.id) }.also {
                    it.tactics.contacts.putAll(old.tactics.contacts)
                }
            }
            squads.clear()
            squads.addAll(replacements)
            replacements.forEach { SquadTactics.refresh(entities.first { npc -> npc.squadId == it.id }) }
            return replacements
        }

        fun enemy(at: Vec3, identity: UUID? = null): NpcEntity =
            AttackDeploymentGameTests.spawn(helper, at, SquadFaction.COW, identity, if (identity == null) null else 79L).apply {
                isNoAi = true
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 50000.0
                health = maxHealth
                entities += this
            }

        /**
         * An ordinary squad with a reported contact: detection, target selection, the gun and the
         * HTN controller all run as in play.
         */
        fun squad(members: List<NpcEntity>, contact: NpcEntity? = null, order: SquadOrder = SquadOrder.ATTACK): Squad =
            manager.create(helper.level, UUID.randomUUID(), requireNotNull(members.first().spawnFaction), members.map { it.uuid }).also {
                squads += it
                manager.setOrder(it.id, order)
                val enemy = contact ?: entities.last { npc -> npc.spawnFaction != members.first().spawnFaction }
                val objective = if (order == SquadOrder.DEFEND || order == SquadOrder.PATROL) {
                    BlockPos.containing(members.fold(Vec3.ZERO) { sum, member -> sum.add(member.position()) }.scale(1.0 / members.size))
                } else {
                    enemy.blockPosition()
                }
                manager.setObjective(helper.level, it.id, objective)
                // Start with a reported contact; this fixture isolates post recovery rather than scout placement.
                val observer = members.firstOrNull { npc -> npc.npcClass == NpcClass.SHOTGUNNER } ?: members.first()
                SquadTactics.observe(observer, enemy)
                helper.assertTrue(enemy.uuid in it.tactics.contacts, "Observer class failed to report its contact")
                SquadTactics.refresh(observer)
                helper.assertTrue(it.tactics.snapshot!!.members.any { member -> member.id == observer.uuid }, "Production assessment excluded the shotgunner")
            }
        fun tick(squad: Squad) {
            val members = entities.filter { it.squadId == squad.id }
            if (helper.tick % 10L == 0L) {
                members.firstOrNull { it.isAlive }?.let(SquadTactics::refresh)
                if (recordTrace) {
                    for (npc in members) {
                        val task = com.sbwnpc.squad.ai.integration.NativeTactics.assignment(npc)
                        val p = helper.relativeVec(npc.position())
                        Files.writeString(report.resolve("$name.csv"), "${helper.tick},${npc.uuid},${npc.npcClass},${p.x},${p.y},${p.z},${task?.job},${npc.navigation.isDone},${npc.lastShotTick},${task?.position},htn\n", APPEND)
                    }
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
