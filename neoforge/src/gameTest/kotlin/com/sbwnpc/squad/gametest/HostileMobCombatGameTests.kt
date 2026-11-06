package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.FiringOpportunity
import com.sbwnpc.squad.combat.GrenadeThrower
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.combat.tactics.TacticalTargetSelection
import com.sbwnpc.squad.domain.port.GrenadeKind
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.function.Consumer

/** Mobs that go for players go for squad NPCs, and are fought by them (#135). */
@PrefixGameTestTemplate(false)
@Suppress("TooManyFunctions")
object HostileMobCombatGameTests {
    /** A roof blocks every alternate throw over the ridge, and an ally blocks its blast. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 100, batch = "grenade-ridge-safety")
    fun ridgeThrowRespectsRoofAndAllies(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "grenade-ridge-safety")
        for (x in 33..40) for (z in 36..46) for (y in 1..2) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        for (z in 36..46) for (y in 1..3) helper.setBlock(BlockPos(44, y, z), Blocks.STONE)
        val man = scene.spawn(Vec3(37.5, 3.0, 41.5)).also {
            it.isNoAi = true
            it.rgnLeft = 2
            it.rgoLeft = 2
        }
        val enemy = scene.enemy(Vec3(49.5, 1.0, 41.5))
        helper.runAfterDelay(1) {
            helper.assertTrue(GrenadeThrower.pick(man, helper.level, enemy.position()) != null, "No clear alternate throw over the ridge")
            for (x in 41..49) for (z in 38..45) helper.setBlock(BlockPos(x, 4, z), Blocks.STONE)
            helper.assertTrue(GrenadeThrower.pick(man, helper.level, enemy.position()) == null, "A grenade could be thrown through the roof")
            for (x in 41..49) for (z in 38..45) helper.setBlock(BlockPos(x, 4, z), Blocks.AIR)
            val ally = scene.spawn(Vec3(49.5, 1.0, 43.5)).also { it.isNoAi = true }
            helper.assertTrue(GrenadeThrower.pick(man, helper.level, enemy.position()) == null, "A grenade could be thrown at an ally")
            ally.discard()
            scene.result("PASS", "roof=blocked ally=blocked")
            helper.succeed()
        }
    }

    /** A squad flushes an enemy last seen below a ridge after he disappears behind it. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "grenade-hidden-below-ridge")
    fun squadFlushesInfantryHiddenBelowRidge(helper: GameTestHelper) = hiddenBelowRidge(helper, GrenadeKind.OFFENSIVE)

    /** A contact-fuzed grenade also reaches the last observed position behind the ridge. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "grenade-impact-below-ridge")
    fun impactGrenadeReachesInfantryBelowRidge(helper: GameTestHelper) = hiddenBelowRidge(helper, GrenadeKind.DEFENSIVE)

    @Suppress("CyclomaticComplexMethod")
    private fun hiddenBelowRidge(helper: GameTestHelper, kind: GrenadeKind) {
        val scene = CombatIdleGameTests.Scene(helper, "grenade-hidden-below-ridge-$kind")
        for (x in 33..40) for (z in 36..46) for (y in 1..2) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        val men = listOf(38.5, 41.5, 44.5).map { z ->
            scene.spawn(Vec3(37.5, 3.0, z)).also {
                it.yRot = -90f
                it.yHeadRot = -90f
            }
        }
        val enemy = scene.enemy(Vec3(49.5, 1.0, 41.5))
        val squad = scene.squad(men, enemy, SquadOrder.DEFEND)
        var projectile: Projectile? = null
        var thrownHidden = false
        val listener = Consumer<EntityJoinLevelEvent> { event ->
            val thrown = event.entity as? Projectile ?: return@Consumer
            val grenade = Ports.grenades.isTimedGrenade(thrown) || thrown is com.atsuishio.superbwarfare.entity.projectile.RgoGrenadeEntity
            if (event.level === helper.level && grenade && men.any { it.uuid == thrown.owner?.uuid }) {
                projectile = thrown
            }
        }
        NeoForge.EVENT_BUS.addListener(listener)
        scene.onClose = { NeoForge.EVENT_BUS.unregister(listener) }
        helper.runAfterDelay(10) {
            for (z in 36..46) for (y in 1..3) helper.setBlock(BlockPos(44, y, z), Blocks.STONE)
            men.forEach {
                it.rgnLeft = if (kind == GrenadeKind.OFFENSIVE) 2 else 0
                it.rgoLeft = if (kind == GrenadeKind.DEFENSIVE) 2 else 0
            }
        }
        helper.onEachTick {
            if (helper.tick > 10 && men.any { plannedGrenadeThrown(it) && !DetectionSightline.canSee(it, enemy) }) {
                thrownHidden = true
            }
            if (thrownHidden && projectile?.position()?.distanceToSqr(enemy.position())?.let { it <= 6.25 } == true) {
                scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
            if (helper.tick == 125L) {
                val contact = squad.tactics.contacts[enemy.uuid]
                val details = men.joinToString { man ->
                    val point = contact?.position ?: enemy.position()
                    "tick=${man.tickCount} blocked=${man.blockedSightSince} safe=${GrenadeThrower.isSafeToThrow(man, point, GrenadeKind.OFFENSIVE)} landing=${Ports.grenades.landing(man, helper.level, point, Vec3.ZERO, GrenadeKind.OFFENSIVE, 2.5, lobs = true)} pos=${helper.relativeVec(man.position())} contactAge=${contact?.let { c -> helper.level.gameTime - c.seenAt }}"
                }
                helper.fail("Grenade did not reach hidden infantry: thrownHidden=$thrownHidden projectile=${projectile?.let { helper.relativeVec(it.position()) }} $details")
            }
        }
    }

    /** Nearby armour does not prevent a safe throw from shelter at infantry to the side. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 800, batch = "grenade-near-armour")
    fun shelteredGrenadierThrowsAtInfantryNearArmour(helper: GameTestHelper) {
        val front = ArmourCoverGameTests.Front(helper, "grenade-near-armour", wall = true, roles = listOf(NpcClass.GRENADIER))
        val man = front.men.single().also {
            it.rgnLeft = 2
            it.rgoLeft = 0
        }
        val enemy = requireNotNull(front.rifleman)
        val point = helper.absoluteVec(Vec3(18.5, 1.0, 40.5))
        enemy.moveTo(point.x, point.y, point.z)
        helper.onEachTick {
            if (helper.tick == 10L) {
                SquadTactics.observe(man, front.crew)
                SquadTactics.observe(man, enemy)
            }
            if (man.rgnLeft < 2) {
                val contact = front.squad.tactics.contacts[front.crew.uuid]
                helper.assertTrue(contact != null && helper.level.gameTime - contact.seenAt <= 100, "The squad had no recent armour contact")
                helper.assertTrue(man.target === enemy, "He did not throw at the infantry")
                helper.assertFalse(front.exposed(man), "He threw while exposed to armour")
                helper.assertTrue(plannedGrenadeThrown(man), "The throw was not made by the grenade plan")
                front.scene.result("PASS", "tick=${helper.tick} grenades=${man.rgnLeft}")
                helper.succeed()
            }
        }
    }

    /** A rifleman uses a reserve grenade against an observed group of infantry. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 800, batch = "rifleman-bunch-throw")
    fun riflemanThrowsAtInfantryBunch(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "rifleman-bunch-throw")
        val man = scene.spawn(Vec3(36.5, 1.0, 41.5)).also {
            it.rgnLeft = 2
            it.rgoLeft = 0
            it.yRot = -90f
            it.yHeadRot = -90f
        }
        val enemies = listOf(40.5, 41.5, 42.5).map { scene.enemy(Vec3(48.5, 1.0, it)) }
        scene.squad(listOf(man), enemies[1], SquadOrder.DEFEND)
        helper.onEachTick {
            if (man.rgnLeft < 2) {
                helper.assertTrue(plannedGrenadeThrown(man), "The throw was not made by the grenade plan")
                scene.result("PASS", "tick=${helper.tick} grenades=${man.rgnLeft}")
                helper.succeed()
            }
        }
    }

    /** A grenadier with a clear target in range carries out a planned hand throw. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "grenadier-hand-throw")
    fun grenadierThrowsAtVisibleInfantry(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "grenadier-hand-throw")
        val man = scene.spawn(Vec3(36.5, 1.0, 41.5), NpcClass.GRENADIER).also {
            it.rgnLeft = 2
            it.rgoLeft = 0
            it.yRot = -90f
            it.yHeadRot = -90f
        }
        val enemy = scene.enemy(Vec3(48.5, 1.0, 41.5))
        scene.squad(listOf(man), enemy, SquadOrder.DEFEND)
        helper.onEachTick {
            if (man.rgnLeft < 2) {
                helper.assertTrue(plannedGrenadeThrown(man), "The throw was not made by the grenade plan")
                scene.result("PASS", "tick=${helper.tick} grenades=${man.rgnLeft}")
                helper.succeed()
            }
        }
    }

    private fun plannedGrenadeThrown(man: NpcEntity): Boolean = NativeTactics.reports(man).values.any { report -> report.evidence.any { it.kind == "grenade_thrown" } }

    /** A defending squad with a mob of [type] nearby, at night so a zombie does not burn. */
    private class MobScene(val helper: GameTestHelper, name: String, type: EntityType<out Mob>) {
        val scene = CombatIdleGameTests.Scene(helper, name)
        val men = (0 until 2).map { scene.spawn(Vec3(40.5, 1.0, 40.5 + it * 2)) }
        val mob: Mob = requireNotNull(type.create(helper.level)).also {
            val at = helper.absoluteVec(Vec3(54.5, 1.0, 41.5))
            it.moveTo(at.x, at.y, at.z, 90f, 0f)
            helper.level.addFreshEntity(it)
        }
        val shots = HashSet<UUID>()
        private val previousDay = helper.level.dayTime
        private val rounds = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile ?: return@Consumer
            val owner = round.owner?.uuid ?: return@Consumer
            if (event.level === helper.level && Ports.guns.isRound(round) && men.any { it.uuid == owner }) shots += owner
        }

        init {
            helper.level.dayTime = 18000L
            val manager = SquadManager.get(helper.level)
            val squad = manager.create(helper.level, UUID(135, 1), requireNotNull(men.first().spawnFaction), men.map { it.uuid })
            manager.issueOrderAt(helper.level, squad.id, SquadOrder.DEFEND, helper.absolutePos(BlockPos(40, 1, 41)))
            NeoForge.EVENT_BUS.addListener(rounds)
            scene.onClose = {
                NeoForge.EVENT_BUS.unregister(rounds)
                manager.deleteSquad(helper.level, squad.id)
                mob.discard()
                helper.level.dayTime = previousDay
            }
        }
    }

    /** A zombie picks a squad NPC as it would a player, and the squad shoots it. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "mob-combat-zombie")
    fun zombieGoesForASquadNpcAndTheSquadShootsIt(helper: GameTestHelper) {
        val probe = MobScene(helper, "mob-combat-zombie", EntityType.ZOMBIE)
        var targeted = false
        helper.onEachTick {
            if (probe.mob.target is NpcEntity) targeted = true
            if (helper.tick % 100L == 0L) probe.scene.result("RUNNING", "tick=${helper.tick} targeted=$targeted shooters=${probe.shots.size} zombieAlive=${probe.mob.isAlive}")
            if (targeted && (probe.shots.isNotEmpty() || !probe.mob.isAlive)) {
                probe.scene.result("PASS", "tick=${helper.tick} shooters=${probe.shots.size} zombieAlive=${probe.mob.isAlive}")
                helper.succeed()
            }
        }
    }

    /** A wild wolf nobody provoked is no enemy: the squad leaves it alone. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "mob-combat-wolf")
    fun unprovokedWolfIsLeftAlone(helper: GameTestHelper) {
        val probe = MobScene(helper, "mob-combat-wolf", EntityType.WOLF)
        helper.onEachTick {
            helper.assertTrue(probe.shots.isEmpty(), "The squad shot at an unprovoked wolf")
            helper.assertTrue(probe.mob.target !is NpcEntity, "An unprovoked wolf went for a squad NPC")
            if (helper.tick >= 300) {
                helper.assertTrue(probe.mob.isAlive, "The wolf died")
                probe.scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /**
     * A squad's LAV a zombie leans on still counts in the fire check. In-game 24.10 a hull the target
     * touched was left out as one it stood in, and the squad's misses went into its own LAV. Checked
     * on [Sightline.vehicleHulls] itself: SBW's collision pushes a still zombie out of the hull, and a
     * live one rarely stays in a shooter's cone long enough for a scene to catch the shot.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 100, batch = "mob-combat-hull")
    fun aHullAZombieLeansOnStaysInTheFireCheck(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "mob-combat-hull")
        val man = scene.spawn(Vec3(36.5, 1.0, 41.5))
        val lav = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25))
        val at = helper.absoluteVec(Vec3(48.5, 1.0, 41.5))
        lav.moveTo(at.x, at.y, at.z, 0f, 0f)
        helper.level.addFreshEntity(lav)
        val zombie = requireNotNull(EntityType.ZOMBIE.create(helper.level)).also {
            it.moveTo(lav.boundingBox.minX - 0.25, at.y, at.z, -90f, 0f)
            it.isNoAi = true
            helper.level.addFreshEntity(it)
        }
        scene.onClose = {
            zombie.discard()
            lav.discard()
        }
        helper.runAfterDelay(1) {
            helper.assertTrue(zombie.boundingBox.intersects(lav.boundingBox), "The zombie does not touch the hull")
            val hulls = Sightline.vehicleHulls(helper.level, man.boundingBox.minmax(zombie.boundingBox).inflate(8.0), man, zombie)
            helper.assertTrue(hulls.any { it == lav.boundingBox }, "The LAV the zombie leans on is out of the fire check")
            scene.result("PASS", "hulls=${hulls.size}")
            helper.succeed()
        }
    }

    /**
     * A lob over a ridge counts only where it lands: aimed above the target, it used to count on
     * passing within 2.5 blocks in mid-air and fly on, an RGO lobbed 2 high at 8 blocks landing at 15.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 100, batch = "grenade-ridge-landing")
    fun ridgeLobLandsAtItsTarget(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "grenade-ridge-landing")
        for (x in 33..40) for (z in 36..46) for (y in 1..2) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        for (z in 36..46) for (y in 1..3) helper.setBlock(BlockPos(44, y, z), Blocks.STONE)
        val man = scene.spawn(Vec3(37.5, 3.0, 41.5)).also { it.isNoAi = true }
        val enemy = scene.enemy(Vec3(49.5, 1.0, 41.5))
        helper.runAfterDelay(1) {
            val landings = GrenadeKind.entries.associateWith { Ports.grenades.landing(man, helper.level, enemy.position(), Vec3.ZERO, it, 2.5, lobs = true) }
            helper.assertTrue(landings.values.any { it != null }, "No lob reaches the target over the ridge")
            landings.forEach { (kind, at) ->
                if (at != null) helper.assertTrue(at.distanceTo(enemy.position()) <= 2.5, "$kind lob lands ${at.distanceTo(enemy.position())} blocks from the target")
            }
            scene.result("PASS", landings.entries.joinToString { "${it.key}=${it.value?.distanceTo(enemy.position())}" })
            helper.succeed()
        }
    }

    /**
     * An RGO goes off on the enemy's body, so an ally standing past the enemy, outside the blast
     * round him, does not stop the throw.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 100, batch = "grenade-ally-behind")
    fun impactGrenadeIsThrownWithAnAllyBehindTheEnemy(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "grenade-ally-behind")
        val man = scene.spawn(Vec3(36.5, 1.0, 41.5)).also {
            it.isNoAi = true
            it.rgnLeft = 0
            it.rgoLeft = 2
        }
        val enemy = scene.enemy(Vec3(46.5, 1.0, 41.5))
        val behind = 46.5 + Ports.grenades.blastRadius(GrenadeKind.DEFENSIVE) + 1.5
        scene.spawn(Vec3(behind, 1.0, 41.5)).also { it.isNoAi = true }
        helper.runAfterDelay(1) {
            helper.assertTrue(GrenadeThrower.pick(man, helper.level, enemy.boundingBox.center) == GrenadeKind.DEFENSIVE, "No RGO was thrown with an ally behind the enemy")
            scene.result("PASS", "ally at x=$behind")
            helper.succeed()
        }
    }

    /**
     * No grenade is thrown where its own blast reaches the thrower, and one is in the open further
     * off. In-game 25.10 a man threw an M67 (6-block blast) at a zombie five blocks away and killed
     * himself and a medic.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 100, batch = "mob-combat-grenade")
    fun grenadeIsNotThrownWithinItsOwnBlast(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "mob-combat-grenade")
        val man = scene.spawn(Vec3(36.5, 1.0, 41.5)).also {
            it.isNoAi = true
            it.rgnLeft = 2
            it.rgoLeft = 0
        }
        val near = scene.enemy(Vec3(42.0, 1.0, 41.5))
        val far = scene.enemy(Vec3(48.5, 1.0, 41.5))
        helper.runAfterDelay(1) {
            helper.assertTrue(GrenadeThrower.pick(man, helper.level, near.boundingBox.center) == null, "A grenade was thrown within its own blast")
            helper.assertTrue(GrenadeThrower.pick(man, helper.level, far.boundingBox.center) != null, "No grenade was thrown in the open")
            scene.result("PASS", "near=null")
            helper.succeed()
        }
    }

    /**
     * A grenade is not thrown where a vehicle's hull stands across its flight, and is with the hull
     * gone. In-game 25.10 one meant for a zombie past a squad's LAV flew on through the hull.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 100, batch = "mob-combat-grenade-hull")
    fun grenadeIsNotThrownIntoAHull(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "mob-combat-grenade-hull")
        val man = scene.spawn(Vec3(36.5, 1.0, 41.5)).also {
            it.isNoAi = true
            it.rgnLeft = 2
            it.rgoLeft = 2
        }
        val enemy = scene.enemy(Vec3(48.5, 1.0, 41.5))
        val lav = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25))
        val at = helper.absoluteVec(Vec3(43.5, 1.0, 41.5))
        lav.moveTo(at.x, at.y, at.z, 90f, 0f)
        helper.level.addFreshEntity(lav)
        scene.onClose = { lav.discard() }
        helper.runAfterDelay(1) {
            helper.assertTrue(GrenadeThrower.pick(man, helper.level, enemy.boundingBox.center) == null, "A grenade was thrown into the hull")
            lav.discard()
            helper.assertTrue(GrenadeThrower.pick(man, helper.level, enemy.boundingBox.center) != null, "No grenade was thrown with the way clear")
            scene.result("PASS", "hull=blocked")
            helper.succeed()
        }
    }

    /**
     * A zombie standing just in front of the squad's empty LAV is no target: the rounds that miss go
     * into the LAV. Clear of it, the zombie is. In-game 25.10 the hull check stopped at the target,
     * and a squad shooting in the dark put its misses into its own vehicle.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 100, batch = "mob-combat-backstop")
    fun zombieInFrontOfTheSquadsVehicleIsNotShotAt(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "mob-combat-backstop")
        val man = scene.spawn(Vec3(36.5, 1.0, 41.5)).also {
            it.isNoAi = true
            it.yRot = -90f
            it.yHeadRot = -90f
        }
        val zombie = requireNotNull(EntityType.ZOMBIE.create(helper.level)).also {
            val at = helper.absoluteVec(Vec3(42.5, 1.0, 41.5))
            it.moveTo(at.x, at.y, at.z, 90f, 0f)
            it.isNoAi = true
            helper.level.addFreshEntity(it)
        }
        val lav = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25))
        val at = helper.absoluteVec(Vec3(47.5, 1.0, 41.5))
        lav.moveTo(at.x, at.y, at.z, 0f, 0f)
        helper.level.addFreshEntity(lav)
        scene.onClose = {
            zombie.discard()
            lav.discard()
        }
        helper.runAfterDelay(2) {
            man.sensing.tick()
            helper.assertTrue(DetectionSightline.canSee(man, zombie), "The man does not see the zombie")
            val withHull = FiringOpportunity.assess(man, zombie)
            helper.assertTrue(withHull != TacticalTargetSelection.Opportunity.SHOOTABLE, "The zombie in front of the LAV was shootable")
            lav.discard()
            val clear = FiringOpportunity.assess(man, zombie)
            helper.assertTrue(clear == TacticalTargetSelection.Opportunity.SHOOTABLE, "The zombie was not shootable with the LAV gone: $clear")
            scene.result("PASS", "withHull=$withHull clear=$clear")
            helper.succeed()
        }
    }
}
