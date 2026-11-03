package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.UUID
import java.util.function.Consumer

/** A few exposed scouts see the enemy, while the ridge blocks most of an already spread line. */
@PrefixGameTestTemplate(false)
object TerrainAttackGameTests {
    /** The ground a height-men scene stands on: a flat field, a hill they can walk up, or a pillar they cannot. */
    private enum class Ground { FLAT, HILL, PILLAR }

    /**
     * A sniper and a machine gunner attacking with four riflemen take the rise by the front (#262):
     * a hill stepped one block at a time, 6 high, between them and the enemy. On flat ground
     * they keep their places in the line, and a sheer pillar, which the heightmap offers as a rise
     * too, is refused once they find no post on it, and they go back to the line.
     */
    private fun heightMen(helper: GameTestHelper, name: String, ground: Ground) {
        AmmunitionTacticsGameTests.floor(helper)
        for (x in 0..FIELD_X) for (z in FIELD_Z) for (y in 1..FIELD_CLEAR) helper.setBlock(BlockPos(x, y, z), Blocks.AIR)
        when (ground) {
            Ground.FLAT -> Unit

            Ground.HILL -> for (x in HILL_FROM..HILL_TO) {
                val top = minOf(x - HILL_FROM, HILL_TO - x, HILL_HEIGHT)
                for (z in HILL_Z) for (y in 1..top) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
            }

            Ground.PILLAR -> for (x in PILLAR_X) for (z in PILLAR_Z) for (y in 1..PILLAR_HEIGHT) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        }
        val scene = CombatIdleGameTests.Scene(helper, name)
        val manager = SquadManager.get(helper.level)
        val heavy = listOf(scene.spawn(Vec3(14.5, 1.0, 60.5), com.sbwnpc.squad.npc.NpcClass.SNIPER), scene.spawn(Vec3(14.5, 1.0, 64.5), com.sbwnpc.squad.npc.NpcClass.MACHINE_GUNNER))
        val riflemen = listOf(56.5, 60.5, 64.5, 68.5).map { scene.spawn(Vec3(10.5, 1.0, it)) }
        val enemy = scene.enemy(Vec3(110.5, 1.0, 64.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, (heavy + riflemen).map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, enemy.blockPosition())
        manager.setFocus(squad.id, enemy.uuid)
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        val chose = HashSet<UUID>()
        var highest = 0.0
        val start = heavy.map { it.position() }
        helper.onEachTick {
            val now = helper.tick
            heavy.filter { man -> com.sbwnpc.squad.ai.diagnostics.DecisionTrace.decisionsOf(man.uuid).any { it.event == "duty" && "<- ${com.sbwnpc.squad.ai.tactics.DutyUtility.OBSERVE_HEIGHT}" in it.detail } }.forEach { chose += it.uuid }
            highest = maxOf(highest, heavy.maxOf { helper.relativeVec(it.position()).y })
            if (now % 50L == 0L) scene.result("RUNNING", "tick=$now chose=${chose.size} highest=$highest at=${heavy.map { helper.relativeVec(it.position()) }}")
            if (ground == Ground.HILL && chose.size == heavy.size && highest >= HILL_HEIGHT - 1) {
                scene.result("PASS", "tick=$now highest=$highest")
                helper.succeed()
            }
            if (ground != Ground.HILL && now >= FLAT_CHECK_TICK) {
                val duties = heavy.map { man -> com.sbwnpc.squad.ai.diagnostics.DecisionTrace.decisionsOf(man.uuid).lastOrNull { it.event == "duty" }?.detail }
                if (ground == Ground.FLAT) helper.assertTrue(chose.isEmpty(), "A height man took a height on flat ground")
                helper.assertTrue(duties.none { it != null && "<- ${com.sbwnpc.squad.ai.tactics.DutyUtility.OBSERVE_HEIGHT}" in it }, "A height man still held a height he cannot climb: $duties")
                helper.assertTrue(heavy.indices.all { heavy[it].position().x - start[it].x >= LINE_ADVANCE }, "The height men did not go up to the line: ${heavy.map { helper.relativeVec(it.position()) }}")
                scene.result("PASS", "tick=$now chose=${chose.size}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 900, batch = "height-men-hill")
    fun sniperAndGunnerInAnAttackTakeTheRiseByTheFront(helper: GameTestHelper) = heightMen(helper, "height-men-hill", Ground.HILL)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 900, batch = "height-men-flat")
    fun sniperAndGunnerOnFlatGroundKeepTheirPlacesInTheLine(helper: GameTestHelper) = heightMen(helper, "height-men-flat", Ground.FLAT)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 900, batch = "height-men-pillar")
    fun sniperAndGunnerRefuseAPillarTheyCannotClimbAndJoinTheLine(helper: GameTestHelper) = heightMen(helper, "height-men-pillar", Ground.PILLAR)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "terrain")
    fun attackPastRidgeWithoutReturnFire(helper: GameTestHelper) = ridge(helper, returnFire = false)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "terrain-return-fire")
    fun attackPastRidgeUnderFire(helper: GameTestHelper) = ridge(helper, returnFire = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "broken-posts")
    fun blockedMinorityResumesFireOnUnevenGround(helper: GameTestHelper) = ridge(helper, returnFire = false, brokenPosts = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "village-cover")
    fun smallSquadFindsFireAroundVillageCover(helper: GameTestHelper) = ridge(helper, returnFire = false, villageCover = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1600, batch = "sustained-attack")
    fun attackContinuesAfterFirstCoveredPositions(helper: GameTestHelper) = ridge(helper, returnFire = false, sustained = true)

    internal fun unevenGround(helper: GameTestHelper) {
        for (x in 44..65) {
            for (z in 65..145) {
                val height = if ((x / 4 + z / 7) % 4 == 0) {
                    2
                } else if ((x + z / 3) % 9 == 0) {
                    1
                } else {
                    0
                }
                for (y in 1..height) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
                if ((x * 3 + z) % 37 == 0) helper.setBlock(BlockPos(x, 0, z), Blocks.AIR)
                helper.setBlock(BlockPos(x, -1, z), Blocks.STONE)
            }
        }
    }

    private fun ridge(helper: GameTestHelper, returnFire: Boolean, brokenPosts: Boolean = false, villageCover: Boolean = false, sustained: Boolean = false) {
        for (x in 0..175) for (z in 0..223) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        if (sustained) {
            // Several reachable rows of chest-high shelter, with gaps for ordinary navigation.
            for (x in listOf(36, 56, 76, 96)) {
                for (z in 14..206 step 6) for (side in -1..1) helper.setBlock(BlockPos(x, 1, z + side), Blocks.STONE)
            }
        } else if (villageCover) {
            for (x in 20..49) for (z in 88..136) helper.setBlock(BlockPos(x, 0, z), Blocks.FARMLAND)
            for (x in 50..51) for (z in 96..128) for (y in 1..4) helper.setBlock(BlockPos(x, y, z), Blocks.OAK_PLANKS)
        } else if (brokenPosts) {
            // Small asymmetric hills and shallow hollows, rather than one regular ramp.
            unevenGround(helper)
        } else {
            for (x in 34..78) {
                for (z in 0..198) {
                    val height = minOf(6, (x - 34) / 3 + 1, (78 - x) / 3 + 1)
                    for (y in 1..height) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
                }
            }
        }
        if (!brokenPosts && !villageCover && !sustained) for (y in 1..9) helper.setBlock(BlockPos(30, y, 112), Blocks.STONE)
        val identityPrefix = UUID.randomUUID().mostSignificantBits
        val members = (0 until if (villageCover) 7 else 32).map { index ->
            AttackDeploymentGameTests.spawn(helper, Vec3(if (sustained) 20.5 else 30.5, if (!brokenPosts && !villageCover && !sustained && index == 16) 10.0 else 1.0, (if (villageCover) 94.5 else 16.5) + index * 6), SquadFaction.PIG, UUID(identityPrefix, index.toLong()))
        }
        val enemies = (0 until if (sustained) 32 else 4).map { index ->
            val point = if (sustained) Vec3(144.5 + index / 8 * 3, 1.0, 94.5 + index % 8 * 3) else Vec3(100.5, 1.0, 92.5 + index * 6)
            AttackDeploymentGameTests.spawn(helper, point, SquadFaction.COW).apply {
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
        val name = if (sustained) {
            "sustained-attack"
        } else if (villageCover) {
            "village-cover"
        } else if (brokenPosts) {
            "blocked-minority"
        } else if (returnFire) {
            "ridge-under-fire"
        } else {
            "ridge-no-fire"
        }
        val directory = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(directory)
        val csv = directory.resolve("$name.csv")
        Files.writeString(csv, "tick,npc,x,y,z,onGround,pathDone,job,plan,phase,ammo,lastShotTick,health,alive,pose,target,blockedSightSince\n")
        val damageCsv = directory.resolve("$name-damage.csv")
        Files.writeString(damageCsv, "tick,npc,health,damage,source,causer,direct\n")
        val damage = Consumer<LivingDamageEvent.Post> { event ->
            if (members.any { it.uuid == event.entity.uuid }) {
                Files.writeString(damageCsv, "${helper.tick},${event.entity.uuid},${event.entity.health},${event.newDamage},${event.source.msgId},${event.source.entity?.uuid},${event.source.directEntity?.uuid}\n", APPEND)
            }
        }
        val shooters = HashSet<UUID>()
        val advanced = HashSet<UUID>()
        var enemyRounds = 0
        val obstructed = HashSet<UUID>()
        val resumedFire = HashSet<UUID>()
        val idleTicks = HashMap<UUID, Int>()
        val longestIdle = HashMap<UUID, Int>()
        val previous = HashMap<UUID, Vec3>()
        val firstPosts = HashMap<UUID, Vec3>()
        val continued = HashSet<UUID>()
        val bullets = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile
            if (event.level === helper.level && round != null && Ports.guns.isRound(round)) {
                val owner = round.owner?.uuid
                if (members.any { it.uuid == owner }) {
                    shooters.add(owner!!)
                    if (owner in obstructed && helper.tick > 400) resumedFire.add(owner)
                }
                if (enemies.any { it.uuid == owner }) enemyRounds++
            }
        }
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(bullets)
        NeoForge.EVENT_BUS.addListener(damage)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        var cleaned = false
        fun cleanup() {
            if (cleaned) return
            cleaned = true
            NeoForge.EVENT_BUS.unregister(bullets)
            NeoForge.EVENT_BUS.unregister(damage)
            NeoForge.EVENT_BUS.unregister(pacer)
            manager.deleteSquad(helper.level, squad.id)
            if (defenders != null) manager.deleteSquad(helper.level, defenders.id)
            (members + enemies).forEach { it.discard() }
            helper.level.removePlayerImmediately(observer, Entity.RemovalReason.DISCARDED)
        }
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
        })
        DebugFlags.enabledGroups = setOf(LogGroup.ORDER, LogGroup.PATH, LogGroup.STUCK)
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(if (sustained) 150 else 100, 1, 101)))
        helper.runAtTickTime(600) {
            if (enemies.any { it.isAlive } && squad.tactics.contacts.isNotEmpty()) {
                helper.assertTrue(squad.order == SquadOrder.ATTACK, "Formation arrival cancelled an attack during enemy contact")
            }
        }
        if (brokenPosts) {
            helper.runAtTickTime(300) {
                val covering = ArrayList<com.sbwnpc.squad.entity.NpcEntity>()
                fun berm(npc: com.sbwnpc.squad.entity.NpcEntity): List<BlockPos> {
                    val p = helper.relativeVec(npc.position())
                    val direction = npc.target!!.position().subtract(npc.position()).multiply(1.0, 0.0, 1.0).normalize()
                    val side = Vec3(-direction.z, 0.0, direction.x)
                    return buildList {
                        for (depth in 2..16) {
                            for (lateral in -3..3) {
                                val base = BlockPos.containing(p.add(direction.scale(depth.toDouble())).add(side.scale(lateral.toDouble())))
                                for (dy in 0..3) {
                                    val block = base.above(dy)
                                    val body = AABB(helper.absolutePos(block))
                                    if ((members + enemies).none { it.isAlive && it.boundingBox.inflate(4.0).intersects(body) }) add(block)
                                }
                            }
                        }
                    }.distinct()
                }
                val candidates = members.filter { npc ->
                    npc.isAlive && npc.onGround() && npc.firedRecently(60) && npc.navigation.isDone &&
                        com.sbwnpc.squad.ai.integration.NativeTactics.assignment(npc)?.job in TacticalJob.FIRE_SUPPORT && npc.target != null &&
                        DetectionSightline.canSee(npc, npc.target!!) && !Sightline.blocked(helper.level, npc.eyePosition, npc.target!!.eyePosition, npc)
                }.sortedBy { helper.relativeVec(it.position()).z }
                for (npc in candidates) {
                    val closesShot = berm(npc).any { AABB(helper.absolutePos(it)).clip(npc.eyePosition, npc.target!!.eyePosition).isPresent }
                    if (closesShot && covering.all { it.position().subtract(npc.position()).horizontalDistance() >= 24.0 }) covering.add(npc)
                    if (covering.size == 4) break
                }
                helper.assertTrue(covering.size == 4, "Scene needs four working cover posts before obstruction")
                for (npc in covering) {
                    val p = helper.relativeVec(npc.position())
                    val target = helper.relativeVec(npc.target!!.position())
                    // A small irregular berm closes this shooter's strip; neighboring lanes remain usable.
                    berm(npc).forEach { helper.setBlock(it, Blocks.STONE) }
                    obstructed.add(npc.uuid)
                    Files.writeString(csv.resolveSibling("$name-obstructions.txt"), "npc=${npc.uuid} position=$p target=$target\n", CREATE, APPEND)
                }
                helper.assertTrue(
                    covering.all { npc ->
                        !DetectionSightline.canSee(npc, npc.target!!) || Sightline.blocked(helper.level, npc.eyePosition, npc.target!!.eyePosition, npc)
                    },
                    "Berms did not block the selected shooters",
                )
            }
        }
        helper.onEachTick {
            for (npc in members) {
                val p = helper.relativeVec(npc.position())
                if (sustained) {
                    val task = com.sbwnpc.squad.ai.integration.NativeTactics.assignment(npc)
                    if (npc.onGround() && npc.navigation.isDone && npc.firedRecently(60) && task?.job in TacticalJob.FIRE_SUPPORT) {
                        firstPosts.putIfAbsent(npc.uuid, npc.position())
                    }
                    val first = firstPosts[npc.uuid]
                    // The attack axis is +X. Lateral movement and initial deployment do not count.
                    if (first != null && npc.x - first.x >= 12.0) continued += npc.uuid
                }
                if (p.x >= 42.0 && npc.onGround()) advanced.add(npc.uuid)
                val trackedPost = villageCover || (brokenPosts && npc.uuid in obstructed)
                if (trackedPost && helper.tick >= 450 && npc.isAlive && Ports.guns.inHand(npc)?.hasAmmo() == true) {
                    val last = previous.getOrPut(npc.uuid) { npc.position() }
                    val target = npc.target
                    val blind = target == null || !DetectionSightline.canSee(npc, target) || Sightline.blocked(helper.level, npc.eyePosition, target.eyePosition, npc)
                    val idle = blind && last.subtract(npc.position()).horizontalDistanceSqr() < 4.0 && !npc.firedRecently(60)
                    if (!idle) previous[npc.uuid] = npc.position()
                    val duration = if (idle) (idleTicks[npc.uuid] ?: 0) + 1 else 0
                    idleTicks[npc.uuid] = duration
                    longestIdle[npc.uuid] = maxOf(longestIdle[npc.uuid] ?: 0, duration)
                }
                if (helper.tick % 10 == 0L) {
                    Files.writeString(csv, "${helper.tick},${npc.uuid},${p.x},${p.y},${p.z},${npc.onGround()},${npc.navigation.isDone},${com.sbwnpc.squad.ai.integration.NativeTactics.assignment(npc)?.job},htn,${com.sbwnpc.squad.ai.integration.NativeTactics.attackPhase(npc)},${Ports.guns.roundsLeft(npc.mainHandItem)},${npc.lastShotTick},${npc.health},${npc.isAlive},${npc.pose},${npc.target?.uuid},${npc.blockedSightSince}\n", APPEND)
                }
            }
        }
        helper.runAtTickTime(if (sustained) 1599 else 1199) {
            try {
                Files.writeString(directory.resolve("$name.txt"), "shooters=${shooters.size}\nadvanced=${advanced.size}\nalive=${members.count { it.isAlive }}\nenemyRounds=$enemyRounds\n")
                if (sustained) {
                    Files.writeString(directory.resolve("$name.txt"), "firstFiringPosts=${firstPosts.size}\ncontinuedAfterFirstPost=${continued.size}\n", APPEND)
                    helper.assertTrue(firstPosts.size >= 24, "Most attackers never established a first physical firing post")
                    helper.assertTrue(continued.size >= 16, "Half the attack did not cross another 12-block bound after first deployment; see $name.csv")
                }
                helper.assertTrue(!returnFire || enemyRounds > 0, "Return-fire control did not fire real rounds")
                helper.assertTrue(returnFire || enemyRounds == 0, "Isolation control fired unexpectedly")
                if (brokenPosts) {
                    for (npc in members.filter { it.uuid in obstructed && (it.uuid !in resumedFire || (longestIdle[it.uuid] ?: 0) >= 200) }) {
                        val audit = directory.resolve("lost-post-search.txt")
                        val task = com.sbwnpc.squad.ai.integration.NativeTactics.assignment(npc)
                        val view = squad.tactics.snapshot
                        if (task == null || view == null) {
                            Files.writeString(audit, "npc=${npc.uuid} position=${helper.relativeVec(npc.position())} task=${task?.job} snapshot=${view != null} longestBlindIdle=${longestIdle[npc.uuid]}\n", CREATE, APPEND)
                            continue
                        }
                        val taken = FiringSpots.nearbyWithBodies(helper.level, npc, 64.0)
                        val free = (-24..24).flatMap { x ->
                            (-24..24).mapNotNull { z ->
                                if (x * x + z * z > 24 * 24) return@mapNotNull null
                                val point = Terrain.feetAt(helper.level, npc.position().add(x.toDouble(), 0.0, z.toDouble())) {
                                    helper.level.noCollision(npc, npc.getDimensions(npc.pose).makeBoundingBox(it))
                                } ?: return@mapNotNull null
                                point.takeIf { !FiringSpots.crowded(it, taken) && TacticalPositions.safeDistance(npc, view, it) && TacticalPositions.firingReady(npc, com.sbwnpc.squad.combat.tactics.TacticalTask(task.job, task.anchor, task.focus, 0), it) }
                            }
                        }.distinct().sortedBy { it.distanceToSqr(npc.position()) }
                        val reachable = free.filter { point ->
                            val path = TacticalPositions.pathToPosition(npc, point)
                            TacticalPositions.reaches(path, point) && TacticalPositions.safePath(npc, view, path!!)
                        }
                        Files.writeString(audit, "npc=${npc.uuid} pose=${npc.pose} target=${npc.target?.uuid} origin=${task.localFirePost?.let(helper::relativeVec)} longestBlindIdle=${longestIdle[npc.uuid]} free=${free.size} reachable=${reachable.map(helper::relativeVec)}\n", CREATE, APPEND)
                    }
                    Files.writeString(directory.resolve("$name.txt"), "obstructed=${obstructed.size}\nresumedFire=${resumedFire.size}\nlongestBlindIdle=${longestIdle.values.maxOrNull() ?: 0}\n", APPEND)
                    helper.assertTrue(resumedFire.size == 4, "Blocked shooters did not resume real fire; see $name.csv")
                    helper.assertTrue(longestIdle.values.none { it >= 200 }, "A shooter stayed blind and idle for ten seconds; see $name.csv")
                } else if (villageCover) {
                    Files.writeString(directory.resolve("$name.txt"), "longestBlindIdle=${longestIdle.values.maxOrNull() ?: 0}\n", APPEND)
                    helper.assertTrue(longestIdle.values.none { it >= 200 }, "A village attacker stayed blind and idle for ten seconds; see $name.csv")
                } else {
                    helper.assertTrue(advanced.size >= if (returnFire) 12 else 24, "Attack failed to advance past the ridge; see $name.csv")
                }
                helper.assertTrue(
                    shooters.size >= if (villageCover) {
                        6
                    } else if (returnFire) {
                        12
                    } else {
                        24
                    },
                    "Most of the attack never fired real rounds; see $name.txt",
                )
                helper.assertTrue(returnFire || members.all { it.isAlive }, "Attack lost NPCs without return fire")
                helper.succeed()
            } finally {
                cleanup()
            }
        }
    }

    /** The hill the height men are to take: its foot and far foot along x, and its width. */
    private const val HILL_FROM = 30
    private const val HILL_TO = 58
    private const val HILL_HEIGHT = 6
    private val HILL_Z = 44..84

    /** A sheer pillar by the front, 8 high, that no man can climb. */
    private val PILLAR_X = 40..42
    private val PILLAR_Z = 62..64
    private const val PILLAR_HEIGHT = 8

    /** The field cleared of the arena's own blocks, so flat is flat. */
    private const val FIELD_X = 130
    private val FIELD_Z = 30..100
    private const val FIELD_CLEAR = 12

    /** By then a height man has gone up to the line, and been given a height if any was offered. */
    private const val FLAT_CHECK_TICK = 500L

    /** How far towards the enemy a height man in the line has come from where he started. */
    private const val LINE_ADVANCE = 10.0
}
