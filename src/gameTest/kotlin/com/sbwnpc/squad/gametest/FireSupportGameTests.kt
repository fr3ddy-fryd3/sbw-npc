package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.entity.projectile.MortarShellEntity
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.TankModel
import com.sbwnpc.squad.squad.FireSupport
import com.sbwnpc.squad.squad.GroupOrders
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer

/** Tanks and mortars sent with infantry in one group attack ([FireSupport]). */
@PrefixGameTestTemplate(false)
object FireSupportGameTests {
    /**
     * Two tanks ordered to attack at once: one with an infantry squad, one alone. Before any
     * contact the escorting tank stays level with the leading infantryman, while the lone one
     * drives at its own speed and gets well ahead.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", skyAccess = true, timeoutTicks = 1600, batch = "fire-support-escort")
    fun tankKeepsInfantryPaceUntilContact(helper: GameTestHelper) {
        for (x in 0..159) for (z in 0..127) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val manager = SquadManager.get(helper.level)
        val created = mutableListOf<Entity>()
        fun tank(z: Double): com.sbwnpc.squad.squad.Squad {
            val vehicle = Ports.vehicles.create(helper.level, TankModel.T_90A)!!
            val point = helper.absoluteVec(Vec3(12.5, 1.0, z))
            vehicle.moveTo(point.x, point.y, point.z, -90f, 0f)
            helper.level.addFreshEntity(vehicle)
            Ports.vehicles.fuelAndArm(vehicle, TankModel.T_90A)
            SquadTeams.assign(vehicle, SquadFaction.PIG)
            created += vehicle
            val crew = (0 until 3).map { AttackDeploymentGameTests.spawn(helper, Vec3(14.5 + it, 1.0, z + 4), SquadFaction.PIG, role = NpcClass.TANK_CREW).apply { assignedVehicleId = vehicle.uuid } }
            created += crew
            return manager.create(helper.level, UUID(92, 1), SquadFaction.PIG, crew.map { it.uuid })
        }
        val escort = tank(30.5)
        val alone = tank(100.5)
        val infantry = (0 until 8).map { AttackDeploymentGameTests.spawn(helper, Vec3(10.5 + it % 4 * 2, 1.0, 40.5 + it / 4 * 2), SquadFaction.PIG) }
        created += infantry
        val rifles = manager.create(helper.level, UUID(92, 1), SquadFaction.PIG, infantry.map { it.uuid })
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                listOf(escort, alone, rifles).forEach { manager.deleteSquad(helper.level, it.id) }
                created.forEach(Entity::discard)
            }
        })
        helper.runAtTickTime(40) {
            helper.assertTrue(manager.isTankSquad(escort) && manager.isTankSquad(alone), "Fixture tanks are not tank squads")
            val front = helper.absolutePos(BlockPos(150, 1, 36))
            GroupOrders.apply(helper.level, manager, listOf(escort.id, rifles.id), com.sbwnpc.squad.squad.SquadOrder.ATTACK, front.x, front.z)
            val other = helper.absolutePos(BlockPos(150, 1, 100))
            GroupOrders.apply(helper.level, manager, listOf(alone.id), com.sbwnpc.squad.squad.SquadOrder.ATTACK, other.x, other.z)
            helper.assertTrue(FireSupport.infantry(helper.level, escort).map { it.id } == listOf(rifles.id), "Escorting tank does not see its infantry")
            helper.assertTrue(FireSupport.infantry(helper.level, alone).isEmpty(), "Lone tank sees infantry it was not sent with")
        }
        val escortVehicle = created[0]
        val aloneVehicle = created[4]
        val log = report.resolve("fire-support-escort.csv")
        Files.writeString(log, "tick,escortX,infantryLeadX,aloneX\n")
        var worstLead = 0.0
        helper.onEachTick {
            if (helper.tick < 60 || helper.tick % 20L != 0L) return@onEachTick
            val escortX = helper.relativeVec(escortVehicle.position()).x
            val leadX = infantry.filter { it.isAlive }.maxOf { helper.relativeVec(it.position()).x }
            val aloneX = helper.relativeVec(aloneVehicle.position()).x
            Files.writeString(log, "${helper.tick},${escortX.toInt()},${leadX.toInt()},${aloneX.toInt()}\n", java.nio.file.StandardOpenOption.APPEND)
            worstLead = maxOf(worstLead, escortX - leadX)
            helper.assertTrue(escortX - leadX <= FireSupport.ESCORT_LEAD + 8, "Escorting tank ran ahead of its infantry: tank x=$escortX, lead x=$leadX")
            if (aloneX - leadX >= 30 && escortX >= 40) {
                Files.writeString(report.resolve("fire-support-escort.txt"), "result=PASS tick=${helper.tick} escortX=${escortX.toInt()} leadX=${leadX.toInt()} aloneX=${aloneX.toInt()} worstLead=${"%.1f".format(worstLead)}\n")
                helper.succeed()
            }
        }
    }

    /**
     * A mortar sent with infantry shells the area round the objective while the infantry is on its
     * way, and stops shelling the area once the infantry has arrived.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", skyAccess = true, timeoutTicks = 1600, batch = "fire-support-mortar")
    fun mortarShellsTheAreaUntilInfantryArrives(helper: GameTestHelper) {
        for (x in 0..159) for (z in 0..127) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val manager = SquadManager.get(helper.level)
        val tube = Ports.mortars.create(helper.level, -90f)
        val pos = helper.absoluteVec(Vec3(24.5, 1.0, 64.5))
        tube.moveTo(pos.x, pos.y, pos.z, -90f, 0f)
        helper.level.addFreshEntity(tube)
        SquadTeams.assign(tube, SquadFaction.PIG)
        val crew = listOf(Vec3(22.5, 1.0, 64.5), Vec3(24.5, 1.0, 66.3)).map { AttackDeploymentGameTests.spawn(helper, it, SquadFaction.PIG, role = NpcClass.MORTAR) }
        val mortars = manager.create(helper.level, UUID(92, 2), SquadFaction.PIG, crew.map { it.uuid })
        val infantry = (0 until 4).map { AttackDeploymentGameTests.spawn(helper, Vec3(10.5 + it * 2, 1.0, 90.5), SquadFaction.PIG).apply { isNoAi = true } }
        val rifles = manager.create(helper.level, UUID(92, 2), SquadFaction.PIG, infantry.map { it.uuid })
        val shells = mutableListOf<Long>()
        val flying = LinkedHashMap<MortarShellEntity, Vec3>()
        val landings = mutableListOf<Double>()
        val listener = Consumer<EntityJoinLevelEvent> { event ->
            val shell = event.entity as? MortarShellEntity ?: return@Consumer
            if (event.level === helper.level && shell.owner?.uuid in crew.map { it.uuid }) {
                shells += helper.tick
                flying[shell] = shell.position()
            }
        }
        NeoForge.EVENT_BUS.addListener(listener)
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                NeoForge.EVENT_BUS.unregister(listener)
                listOf(mortars, rifles).forEach { manager.deleteSquad(helper.level, it.id) }
                crew.forEach { MortarClaims.release(it.uuid) }
                MortarClaims.releaseMortar(tube.uuid)
                (crew + infantry + tube).forEach(Entity::discard)
            }
        })
        val objective = helper.absolutePos(BlockPos(105, 1, 64))
        helper.runAtTickTime(20) {
            GroupOrders.apply(helper.level, manager, listOf(mortars.id, rifles.id), com.sbwnpc.squad.squad.SquadOrder.ATTACK, objective.x, objective.z)
            helper.assertTrue(FireSupport.infantryApproaching(helper.level, mortars), "Mortar does not see its infantry approaching")
        }
        var arrivedAt: Long? = null
        helper.onEachTick {
            // Where each shell came down: its last position before it was removed.
            val tracked = flying.entries.iterator()
            while (tracked.hasNext()) {
                val entry = tracked.next()
                if (entry.key.isRemoved) {
                    if (arrivedAt == null) landings += Math.hypot(entry.value.x - objective.center.x, entry.value.z - objective.center.z)
                    tracked.remove()
                } else {
                    entry.setValue(entry.key.position())
                }
            }
            if (arrivedAt == null && landings.size >= 4) {
                // Aimed at the point, shells land within the crew's scatter (at most 10 blocks);
                // shelling the 40-block area puts some well beyond it.
                helper.assertTrue(landings.max() >= 18.0, "Mortar shelled the point, not the area: landings=$landings")
                // The infantry gets there: the area fire lifts.
                infantry.forEachIndexed { i, npc -> npc.teleportTo(objective.x + 20.5 + i * 2, objective.y.toDouble(), objective.z + 0.5) }
                arrivedAt = helper.tick
            }
            val arrived = arrivedAt ?: return@onEachTick
            if (helper.tick == arrived + 5) helper.assertTrue(!FireSupport.infantryApproaching(helper.level, mortars), "Arrived infantry still counted as approaching")
            if (helper.tick >= arrived + 300) {
                val after = shells.count { it > arrived + 20 }
                Files.writeString(report.resolve("fire-support-mortar.txt"), "result=PASS shells=$shells landings=${landings.map { it.toInt() }} arrivedAt=$arrived afterArrival=$after\n")
                helper.succeed()
            }
        }
    }
}
