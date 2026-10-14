package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.GroupOrders
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID
import java.util.function.Consumer

/** Public commands, production HTN, real navigation and SBW projectiles; no forced tactical plans. */
@PrefixGameTestTemplate(false)
object HtnTacticsGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1600, batch = "htn-compact")
    fun htnJointAttack64OnCompactFront(helper: GameTestHelper) = jointAttack(helper, wide = false)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1600, batch = "htn-wide")
    fun htnJointAttack64OnWideFront(helper: GameTestHelper) = jointAttack(helper, wide = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1800, batch = "htn-area")
    fun htnEmptyAreaRequiresOccupationAndQuietInterval(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-area")
        val manager = SquadManager.get(helper.level)
        val actors = (0 until 4).map { scene.spawn(Vec3(24.5, 1.0, 80.5 + it * 3)) }
        val squad = manager.create(helper.level, UUID(90, 3), requireNotNull(actors.first().spawnFaction), actors.map { it.uuid })
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        val goal = helper.absolutePos(BlockPos(84, 1, 86))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, goal)
        val original = requireNotNull(squad.command)
        var occupiedAt: Long? = null
        var injectedAt: Long? = null
        helper.onEachTick {
            val occupied = actors.count { it.position().distanceTo(goal.center) <= 24 } >= 2
            if (occupied && occupiedAt == null) occupiedAt = helper.tick
            if (occupiedAt != null && injectedAt == null && helper.tick - occupiedAt!! >= 300) {
                actors.forEach { it.incomingFire.record(it.position(), Vec3(-1.0, 0.0, 0.0), null, helper.level.gameTime) }
                injectedAt = helper.tick
            }
            helper.assertTrue(squad.command == original, "Internal mode changed the requested order")
            if (squad.order == SquadOrder.DEFEND) {
                helper.assertTrue(occupiedAt != null && injectedAt != null, "Attack completed before physical occupation and renewed threat")
                helper.assertTrue(helper.tick - injectedAt!! >= 600, "Renewed incoming fire did not reset success interval")
                helper.assertTrue(squad.commandOutcome == com.sbwnpc.squad.ai.tactics.CommandOutcome.COMPLETED, "Physical success was not recorded")
                helper.assertTrue(squad.tactics.plan == null, "Legacy controller completed the attack")
                scene.result("PASS", "tick=${helper.tick} occupiedAt=$occupiedAt incomingAt=$injectedAt reason=${squad.commandReason}")
                helper.succeed()
            }
        }
    }

    private fun jointAttack(helper: GameTestHelper, wide: Boolean) {
        val name = if (wide) "htn-wide" else "htn-compact"
        val scene = CombatIdleGameTests.Scene(helper, name)
        val manager = SquadManager.get(helper.level)
        val principal = UUID(90, if (wide) 2 else 1)
        val groups = (0..1).map { squad ->
            (0 until 32).map { index ->
                scene.spawn(Vec3(24.5 + index / 8 * 3, 1.0, 68.5 + squad * 56 + index % 8 * 3))
            }
        }
        val members = groups.flatten()
        val origins = members.associate { it.uuid to it.position() }
        (0 until 16).forEach { index -> scene.enemy(Vec3(145.5 + index / 4 * 2, 1.0, if (wide) 16.5 + index * 12 else 100.5 + index % 4 * 4)) }
        val squads = groups.map { group -> manager.create(helper.level, principal, requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.writeString(report.resolve("$name-observed.csv"), "tick,squad,alive,moved,shootersRecent,actualProjectileShooters,frontWidth,rearGuards,htnExecutions,friendlyHits\n")
        val shooters = HashSet<UUID>()
        val memberIds = origins.keys
        var friendlyHits = 0
        val rounds = Consumer<EntityJoinLevelEvent> { event ->
            val projectile = event.entity as? Projectile ?: return@Consumer
            if (event.level === helper.level && Ports.guns.isRound(projectile) && projectile.owner?.uuid in memberIds) shooters += projectile.owner!!.uuid
        }
        val damage = Consumer<LivingDamageEvent.Post> { event ->
            if (event.entity.uuid in memberIds && event.source.entity?.uuid in memberIds && event.newDamage > 0) friendlyHits++
        }
        NeoForge.EVENT_BUS.addListener(rounds)
        NeoForge.EVENT_BUS.addListener(damage)
        scene.onClose = {
            NeoForge.EVENT_BUS.unregister(rounds)
            NeoForge.EVENT_BUS.unregister(damage)
            squads.forEach { manager.deleteSquad(helper.level, it.id) }
        }
        val objective = helper.absolutePos(BlockPos(148, 1, 106))
        GroupOrders.apply(helper.level, manager, squads.map { it.id }, SquadOrder.ATTACK, objective.x, objective.z)
        var validWindows = 0
        helper.onEachTick {
            if (helper.tick % 100L != 0L) return@onEachTick
            val allViews = members.mapNotNull { NativeTactics.assignment(it) }
            val width = allViews.maxOfOrNull { it.anchor.z }?.minus(allViews.minOf { it.anchor.z }) ?: 0.0
            val rear = allViews.count { it.job == TacticalJob.RESERVE }
            val watchingRear = members.count { actor ->
                val post = NativeTactics.assignment(actor)
                post?.job == TacticalJob.RESERVE && post.focus?.let { actor.lookAngle.dot(it.subtract(actor.eyePosition).normalize()) > 0.5 } == true
            }
            val htn = members.count { NativeTactics.reports(it).isNotEmpty() }
            val moved = members.count { it.position().distanceTo(origins.getValue(it.uuid)) >= 12.0 }
            val useful = groups.all { group -> group.count { it.firedRecently(100) } >= 8 }
            groups.forEachIndexed { index, group ->
                Files.writeString(report.resolve("$name-observed.csv"), "${helper.tick},$index,${group.count { it.isAlive }},${group.count { it.position().distanceTo(origins.getValue(it.uuid)) >= 12.0 }},${group.count { it.firedRecently(100) }},${group.count { it.uuid in shooters }},$width,$rear,$htn,$friendlyHits\n", APPEND)
            }
            if (helper.tick >= 600 && useful && moved >= 32 && shooters.size >= 24 && htn == 64) validWindows++ else validWindows = 0
            if (validWindows >= 3) {
                helper.assertTrue(members.all { it.isAlive }, "Attacker died without return fire")
                helper.assertTrue(friendlyHits == 0, "HTN attack caused friendly damage")
                helper.assertTrue(squads.all { it.tactics.plan == null }, "Legacy tactical controller ran alongside HTN")
                if (!wide) {
                    helper.assertTrue(rear >= 8, "Compact enemy used all surplus capacity on the front")
                    helper.assertTrue(watchingRear >= 4, "Rear guards did not physically watch their assigned sector: $watchingRear/$rear")
                }
                scene.result("PASS", "tick=${helper.tick} moved=$moved actualShooters=${shooters.size} recent=${groups.map { group -> group.count { it.firedRecently(100) } }} width=$width rear=$rear watchingRear=$watchingRear htn=$htn friendlyHits=$friendlyHits")
                helper.succeed()
            }
        }
    }
}
