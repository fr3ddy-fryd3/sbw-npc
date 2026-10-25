package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.FiringOpportunity
import com.sbwnpc.squad.combat.GrenadeThrower
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.tactics.TacticalTargetSelection
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
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
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.function.Consumer

/** Mobs that go for players go for squad NPCs, and are fought by them (#135). */
@PrefixGameTestTemplate(false)
object HostileMobCombatGameTests {
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
