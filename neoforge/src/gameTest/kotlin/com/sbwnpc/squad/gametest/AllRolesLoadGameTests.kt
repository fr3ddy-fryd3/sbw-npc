package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.npc.HelicopterModel
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.function.Consumer

/**
 * Every role at once, for #192: interactions between roles show up under load before they show in a
 * playtest. Single-role scenes cannot see them.
 */
@PrefixGameTestTemplate(false)
object AllRolesLoadGameTests {
    private const val EXPECTED_MEN = 200
    private const val ENDS_AT = 2400L

    /** One deployment slot of a faction's half of the arena. */
    private class Slot(val config: SquadToolItem.Config)

    private fun config(faction: SquadFaction, preset: SquadPreset, role: NpcClass = NpcClass.RIFLEMAN, vehicle: Boolean = false) = SquadToolItem.Config(role, NpcRank.RECRUIT, faction, preset, vehicle = vehicle, heliModel = HelicopterModel.AH_6)

    /** Infantry of every size, two squads carried by an APC, fire support, armour, drones, an aircraft and lone grenadiers. */
    private fun roster(faction: SquadFaction): List<Slot> =
        List(4) { Slot(config(faction, SquadPreset.SIXTEEN)) } +
            List(2) { Slot(config(faction, SquadPreset.SEVEN, vehicle = true)) } +
            List(2) { Slot(config(faction, SquadPreset.FIVE)) } +
            Slot(config(faction, SquadPreset.MORTAR_CREW)) +
            Slot(config(faction, SquadPreset.T90_CREW)) +
            Slot(config(faction, SquadPreset.DRONE_TEAM)) +
            Slot(config(faction, SquadPreset.HELI_CREW)) +
            List(4) { Slot(config(faction, SquadPreset.SINGLE, role = NpcClass.GRENADIER)) }

    /** Two columns of eight slots per side, 24 blocks apart, so no squad spawns inside another. */
    private fun place(index: Int, west: Boolean): Vec3 {
        val column = index % 2
        val x = if (west) 20.5 + column * 30 else 150.5 - column * 30
        return Vec3(x, 1.0, 12.5 + index / 2 * 24)
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 2700, batch = "all-roles-load")
    fun allRolesFightUnderLoad(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "all-roles-load", recordTrace = false)
        val manager = SquadManager.get(helper.level)
        val owner = UUID(92, 192)

        val deployed = listOf(SquadFaction.PIG to true, SquadFaction.COW to false).flatMap { (faction, west) ->
            roster(faction).mapIndexed { index, slot -> faction to scene.deploy(slot.config.preset, place(index, west), slot.config) }
        }
        val members = deployed.flatMap { it.second }
        helper.assertTrue(members.size >= EXPECTED_MEN, "The roster deployed ${members.size} men, fewer than the $EXPECTED_MEN the scene is for")

        val squads = deployed.filter { it.second.size > 1 || it.second.single().npcClass == NpcClass.TANK_CREW }.map { (faction, men) ->
            men to manager.create(helper.level, owner, faction, men.map { it.uuid })
        }
        val work = NativeCombatWork(helper, members)
        val shooters = HashSet<UUID>()
        val rounds = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile ?: return@Consumer
            val shooter = round.owner?.uuid ?: return@Consumer
            if (event.level === helper.level && Ports.guns.isRound(round)) shooters += shooter
        }
        NeoForge.EVENT_BUS.addListener(rounds)
        scene.onClose = {
            work.close()
            NeoForge.EVENT_BUS.unregister(rounds)
            squads.forEach { (_, squad) -> manager.deleteSquad(helper.level, squad.id) }
        }

        val reports = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        val timeline = reports.resolve("all-roles-load-timeline.csv")
        Files.writeString(timeline, "tick,shootersSoFar,alive\n")
        val metrics = RoleLoadMetrics(members.associate { it.uuid to it.npcClass })
        val eastCentre = BlockPos.containing(helper.absoluteVec(Vec3(135.0, 1.0, 100.0)))
        squads.forEach { (men, squad) ->
            val centre = BlockPos.containing(men.map { it.x }.average(), men.first().y, men.map { it.z }.average())
            if (squad.faction == SquadFaction.PIG) manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, eastCentre) else manager.issueOrderAt(helper.level, squad.id, SquadOrder.DEFEND, centre)
        }

        helper.onEachTick {
            if (helper.tick >= 200 && helper.tick % RoleLoadMetrics.SAMPLE_TICKS == 0L) {
                members.forEach { npc -> sample(metrics, helper.tick, npc) }
            }
            if (helper.tick % 200L == 0L) {
                Files.writeString(timeline, "${helper.tick},${shooters.size},${members.count { it.isAlive }}\n", StandardOpenOption.APPEND)
            }
        }
        helper.runAtTickTime(ENDS_AT) {
            work.report(enforce = false)
            val summary = metrics.report(helper.tick)
            Files.writeString(reports.resolve("all-roles-load-roles.txt"), "men=${members.size} shooters=${shooters.size} tick=${helper.tick}\n$summary")
            helper.assertTrue(shooters.size >= 20, "Too few men fired for a fight to measure: ${shooters.size}/${members.size}")
            scene.result("PASS", "men=${members.size} shooters=${shooters.size} alive=${members.count { it.isAlive }}; see all-roles-load-roles.txt, all-roles-load-timeline.csv and operation-work.txt")
            helper.succeed()
        }
    }

    private fun sample(metrics: RoleLoadMetrics, tick: Long, npc: NpcEntity) {
        val job = NativeTactics.assignment(npc)?.job?.name
        metrics.sample(tick, RoleLoadMetrics.Reading(npc.uuid, npc.isAlive, npc.x, npc.z, job, npc.level().gameTime - (npc.tickCount - npc.lastShotTick)))
    }
}
