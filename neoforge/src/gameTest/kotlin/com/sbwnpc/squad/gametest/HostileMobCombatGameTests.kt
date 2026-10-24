package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
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
}
